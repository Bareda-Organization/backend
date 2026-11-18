package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.dto.DelayRequest;
import src.backend.run.repository.RunRepository;
import testsupport.concurrency.PgLockWait;

/**
 * 지연 알림 신고는 회차 행 잠금 아래에서 "운행 중인가 · 같은 내용을 방금 보냈는가" 를 판정한다 — 잠금이 없으면 같은 내용의 동시 두 요청이 둘 다 중복 검사를 지나 학부모 전원에게 같은 푸시가
 * 두 번 가고, 운행이 끝난 직후 도착한 요청도 낡은 상태로 통과한다.
 *
 * <p>시험 스레드가 회차 행 잠금을 쥔 채 요청 스레드를 띄운다. 잠금이 있으면 요청 스레드가 그 잠금에서 줄을 서고(대기 수로 확인), 잠금이 없으면 곧바로 끝난다 — 어느 쪽이든 고정 대기 없이
 * 시험이 다음 단계로 간다.
 */
@SpringBootTest
class DelayNotificationRowLockTest {

    private static final DelayRequest SAME = new DelayRequest(10, "traffic", null);

    @Autowired private ApplicationContext applicationContext;
    @Autowired private DelayNotificationCommandService delayService;
    @Autowired private RunRepository runRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 두 요청이 같은 순간에 중복 검사로 들어가게 모으는 지점 — 잠금이 없으면 둘이 함께 통과한다. */
    @MockitoSpyBean private RunAssignmentAccess runAssignmentAccess;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void 뒷정리한다() {
        executor.shutdownNow();
        String academies = "(SELECT id FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("같은 내용의 지연 신고 두 건이 동시에 와도 알림은 한 번이고 나머지는 409 DELAY_DUPLICATE 다")
    void 같은_내용의_동시_신고는_한_번만_나간다() throws Exception {
        Setup s = 운행_중_회차를_만든다();
        CyclicBarrier together = new CyclicBarrier(2);
        doAnswer(invocation -> {
            try {
                together.await(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 한 스레드만 도착하는 경로(잠금 앞에서 이미 줄을 선 경우)는 기다리지 않고 지나간다
            }
            return invocation.callRealMethod();
        }).when(runAssignmentAccess).assertAssignedEscort(any(), anyLong());

        List<Future<String>> results = new ArrayList<>();
        잠금을_쥔_채(s, () -> {
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> 신고한다(s)));
            }
            PgLockWait.awaitWaitersOr(jdbcTemplate, 2, () -> results.stream().allMatch(Future::isDone));
        });

        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes).as("한 건은 성공, 한 건은 중복").containsExactlyInAnyOrder("OK", ErrorCode.DELAY_DUPLICATE.name());
        assertThat(지연_알림_건수(s)).as("같은 지연이 두 번 기록되면 학부모 전원이 같은 푸시를 두 번 받는다").isEqualTo(1);
    }

    @Test
    @DisplayName("신고가 잠금을 기다리는 사이 운행이 끝나면 409 RUN_NOT_MOVING 이고 알림은 남지 않는다")
    void 그_사이_끝난_운행에는_신고가_거절된다() throws Exception {
        Setup s = 운행_중_회차를_만든다();

        Future<String> result = 잠금을_쥔_채(s, () -> {
            Future<String> pending = executor.submit(() -> 신고한다(s));
            PgLockWait.awaitWaitersOr(jdbcTemplate, 1, pending::isDone);
            jdbcTemplate.update("UPDATE run SET status = 'finished', finished_at = now(), started_at = COALESCE(started_at, now()) WHERE id = ?", s.runId);
            return pending;
        });

        assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RUN_NOT_MOVING.name());
        assertThat(지연_알림_건수(s)).isZero();
    }

    /** 회차 행 잠금을 쥔 트랜잭션 안에서 {@code body} 를 실행한다 — 트랜잭션이 끝나야 잠금이 풀린다. */
    private <T> T 잠금을_쥔_채(Setup s, ThrowingSupplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            runRepository.findLockedByIdAndAcademyId(s.runId, s.academyId).orElseThrow();
            try {
                return body.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void 잠금을_쥔_채(Setup s, ThrowingRunnable body) {
        잠금을_쥔_채(s, () -> {
            body.run();
            return null;
        });
    }

    /** 신고 결과를 "OK" 또는 업무 오류 코드 이름으로 돌려준다. */
    private String 신고한다(Setup s) {
        try {
            delayService.notifyDelay(new AuthUser(s.escortAccountId, s.academyId, Role.ESCORT, AccountStatus.ACTIVE),
                    s.runId, SAME);
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        }
    }

    private int 지연_알림_건수(Setup s) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delay_notice WHERE run_id = ?", Integer.class, s.runId);
    }

    private Setup 운행_중_회차를_만든다() {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        fx.startRun(runId, now);
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now);
        return new Setup(academyId, runId, escortAccountId);
    }

    private record Setup(long academyId, long runId, long escortAccountId) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("운행 중이 아닌 회차의 지연 신고는 잠금 없이도 RUN_NOT_MOVING 이다")
    void 운행_중이_아니면_거절된다() {
        Setup s = 운행_중_회차를_만든다();
        jdbcTemplate.update("UPDATE run SET status = 'finished', finished_at = now(), started_at = COALESCE(started_at, now()) WHERE id = ?", s.runId);

        assertThatThrownBy(() -> delayService.notifyDelay(
                new AuthUser(s.escortAccountId, s.academyId, Role.ESCORT, AccountStatus.ACTIVE), s.runId, SAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RUN_NOT_MOVING));
    }
}
