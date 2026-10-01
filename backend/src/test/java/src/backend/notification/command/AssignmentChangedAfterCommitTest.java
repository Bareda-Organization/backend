package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.notification.domain.impl.AssignmentChangedComposer;

/**
 * 배치 변경 통지(Ruling 330)는 커밋 뒤 콜백이 아니라 <b>알림 실행기</b>에서 새 트랜잭션으로 적재한다(R46 T-4) — 커밋 뒤
 * 콜백은 바깥 트랜잭션의 연결을 아직 쥔 채 도므로, 거기서 새 트랜잭션을 열면 한 요청이 연결 2개를 동시에 쥔다(풀이 찬
 * 때 서로 기다리는 교착). 통지 실패·제출 거절이 이미 커밋된 배치를 되돌리지 않는 성질은 그대로다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 커밋 뒤에만 도는 리스너라 실제 커밋이 필요하다. 시드 매니저 1(학원 1)과
 * 다른 시험이 쓰지 않는 회차 식별자로 만든 행만 지운다({@link AssignmentChangedNotificationRunIdTest} 와 같은 형태).
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AssignmentChangedAfterCommitTest {

    private static final long SEED_MANAGER_ID = 1L;

    private static final long SEED_ACADEMY_ID = 1L;

    private static final long UNUSED_RUN_ID = 9_876_543_211L;

    private static final String DEDUP_PREFIX = "assignment_changed:" + UNUSED_RUN_ID + ":";

    @MockitoSpyBean
    private AssignmentChangedComposer composer;

    @MockitoSpyBean(name = "notificationDispatchExecutor")
    private ThreadPoolTaskExecutor notificationDispatchExecutor;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 적재한_행을_지운다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", DEDUP_PREFIX + "%");
    }

    @Test
    @DisplayName("T-4 — 통지 적재는 알림 실행기 스레드의 새 트랜잭션에서 돈다(커밋한 요청 스레드가 아니라)")
    void 통지_적재는_알림_실행기에서_돈다() {
        List<String> composeThreads = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(invocation -> {
            composeThreads.add(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(composer).compose(any(AssignmentChangedEvent.class));

        커밋한다();

        await().atMost(Duration.ofSeconds(10)).until(() -> notificationCount() == 1);
        assertThat(composeThreads).as("문구 조립 = 적재가 한 번 돌았다").hasSize(1);
        assertThat(composeThreads.get(0)).as("커밋한 스레드(%s)가 아니라 알림 실행기에서 돈다", Thread.currentThread().getName())
                .startsWith("notification-dispatch-");
    }

    @Test
    @DisplayName("T-4 — 실행기가 가득 차 제출이 거절돼도 이미 커밋된 배치의 호출자에게 예외가 퍼지지 않고 통지만 빠진다")
    void 제출이_거절돼도_예외가_퍼지지_않는다(CapturedOutput output) {
        Mockito.doThrow(new TaskRejectedException("대기열 가득")).when(notificationDispatchExecutor).execute(any());

        assertThatCode(this::커밋한다).as("커밋된 배치를 통지 실패로 뒤집을 수 없다").doesNotThrowAnyException();

        assertThat(notificationCount()).as("통지는 빠진다").isZero();
        assertThat(output.getAll()).as("제출 거절은 우리 경고 한 줄로 남는다(프레임워크의 콜백 오류 로그가 아니라)")
                .contains("배치 변경 통지 대기열이 가득 차 통지를 건너뛴다");
    }

    private void 커밋한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                new AssignmentChangedEvent(UNUSED_RUN_ID, SEED_ACADEMY_ID, SEED_MANAGER_ID, ManagerRole.DRIVER,
                        OffsetDateTime.now())));
    }

    private int notificationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'assignment_changed' AND dedup_key LIKE ?",
                Integer.class, DEDUP_PREFIX + "%");
        return count == null ? 0 : count;
    }
}
