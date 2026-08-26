package src.backend.bus.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.bus.dto.BusRegisterRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 호차 유일성의 <b>동시성</b> — 같은 학원에 같은 호차 등록 요청 2건이 겹쳐 들어오는 상황
 * (Ruling 164 · 게이트 리뷰 Important 4).
 *
 * <p>애플리케이션 선검사만으로는 이 형태를 막지 못한다. 두 트랜잭션은 서로의 미커밋 INSERT 를 보지
 * 못해 <b>둘 다</b> "그 호차 없음" 을 읽고 지나가며, 그 뒤 {@code uk_bus_academy_bus_no} 가 하나를
 * 거부한다. 그 거부가 {@code 409} 로 옮겨지지 않으면 사용자에게 {@code 500} 이 나간다 — 리뷰어가
 * 프로브로 그 형태를 실측했다.
 *
 * <p>이 클래스에 {@code @Transactional} 이 부재한 것이 요점이다 — 테스트가 트랜잭션을 하나 열고
 * 있으면 두 스레드가 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다. 대신
 * 만든 행을 {@link #뒷정리한다()} 가 직접 지운다({@code AcademyStaffQuotaConcurrencyTest} 와 같은 형태).
 */
@SpringBootTest
class BusRegistrationConcurrencyTest {

    /** 시드와 겹치지 않는 호차 — 뒷정리가 이 값으로 자기 행만 지운다. */
    private static final String BUS_NO = "P5T2CONC호차";

    /** 시드의 학원 A 와 그 관계자 계정. */
    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    /**
     * 스레드 하나가 상대를 기다리는 상한 — <b>정상 흐름에서는 소진되지 않는다.</b> 여기 걸리면
     * 상대가 죽었거나 DB 잠금이 안 풀린 것이라, 테스트가 매달리는 대신 실패로 드러나야 한다.
     */
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private BusCommandService busCommandService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 앞선_실행이_남긴_행을_지운다() {
        뒷정리한다();
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자기 호차 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM bus WHERE bus_no = ?", BUS_NO);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 DUPLICATE_BUS_NO} 다.
     *
     * <p>실패한 쪽이 선검사에서 걸렸는지 DB 에서 걸렸는지는 타이밍이 정하며 여기서 가리지 않는다 —
     * <b>어느 쪽이든 같은 코드로 나가야 한다</b> 는 것이 이 단언의 내용이다. 세 단언이 각각 다른
     * 사고를 막는다: 성공 1건(둘 다 통과하면 중복 행) · 실패가 {@code DUPLICATE_BUS_NO}(500 누출) ·
     * DB 행 1개(응답과 저장 상태가 갈리는 것).
     */
    @Test
    void 같은_학원에_같은_호차를_동시에_등록하면_성공_1건_실패_1건이고_500_이_부재한다() throws Exception {
        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        CountDownLatch 나중도_착수했다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 등록한다("10가0001", 먼저_들어갔다, 나중도_착수했다));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                나중도_착수했다.countDown();
                return 등록한다("10가0002", null, null);
            });

            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().filter(Optional::isEmpty).count())
                .as("성공은 정확히 1건 — 2건이면 같은 호차가 두 번 등록된 것이고 0건이면 정상 등록까지 막힌 것이다")
                .isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("실패는 500(INTERNAL_ERROR)이 아니라 409 DUPLICATE_BUS_NO 여야 한다")
                .containsExactly(ErrorCode.DUPLICATE_BUS_NO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bus WHERE academy_id = ? AND bus_no = ?", Integer.class,
                ACADEMY_A_ID, BUS_NO))
                .as("응답과 무관하게 DB 에 남은 행도 1개여야 한다")
                .isEqualTo(1);
    }

    /**
     * 한 트랜잭션 안에서 차량을 등록한다 — {@code POST /staff/buses} 가 할 일과 같다.
     *
     * <p>두 신호로 겹침을 만든다. 고정 대기({@code Thread.sleep})를 쓰지 않는 이유는 그 값이 기계
     * 속도에 묶여, 느린 기계에서는 겹침이 사라지고 빠른 기계에서는 그만큼 매번 낭비되기 때문이다.
     *
     * <p>예외를 {@link BusinessException} 으로만 받는다 — 그 밖의 예외(예: 번역되지 않은
     * {@code DataIntegrityViolationException})는 여기서 삼키지 않고 그대로 올라가 테스트를 실패시킨다.
     * 삼키면 {@code 500} 누출이 "실패 1건" 으로 세어져 이 테스트가 사고를 통과시킨다.
     *
     * @param 들어갔음   INSERT 를 보낸 직후 내리는 신호 — 상대가 이때부터 착수한다
     * @param 상대가_착수 상대가 착수했다는 신호. 이것을 받고서야 커밋한다. {@code null} 이면 바로 커밋
     * @return 성공이면 빈 값, 호차 충돌이면 그 에러 코드
     */
    private Optional<ErrorCode> 등록한다(String plateNo, CountDownLatch 들어갔음, CountDownLatch 상대가_착수) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                busCommandService.register(관계자A(), new BusRegisterRequest(BUS_NO, plateNo, 16, null));
                if (들어갔음 != null) {
                    들어갔음.countDown();
                }
                상대가_착수할_때까지_커밋을_미룬다(상대가_착수);
                return Optional.<ErrorCode>empty();
            });
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    private void 상대가_착수할_때까지_커밋을_미룬다(CountDownLatch 상대가_착수) {
        if (상대가_착수 == null) {
            return;
        }
        try {
            상대가_착수.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AuthUser 관계자A() {
        return new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
    }
}
