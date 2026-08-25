package src.backend.academy.command;

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

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 정원 판정의 <b>동시성</b> — 같은 학원에 승인 요청 2건이 겹쳐 들어오는 상황(Phase 3 목표 2).
 *
 * <p>애플리케이션 선검사만으로는 이 형태를 막지 못한다. 두 트랜잭션은 서로의 미커밋 INSERT 를 보지
 * 못해 <b>둘 다</b> "재직자 0명" 을 읽고 지나가며, 그 뒤 조건부 UNIQUE 가 하나를 거부한다. 그 거부가
 * {@code 409} 로 옮겨지지 않으면 사용자에게 {@code 500} 이 나간다.
 *
 * <p>이 클래스만 {@code @Transactional} 이 부재하다 — 테스트가 트랜잭션을 하나 열고 있으면 두 스레드가
 * 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다. 대신 만든 행을
 * {@link #뒷정리한다()} 가 직접 지운다.
 */
@SpringBootTest
class AcademyStaffQuotaConcurrencyTest {

    private static final String ACADEMY_CODE = "P3T1CONC";

    /** 먼저 들어간 트랜잭션이 INSERT 를 보낸 뒤 커밋을 미루는 시간 — 뒤 요청이 같은 창에 들어오게 한다. */
    private static final long HOLD_MILLIS = 500;

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private AcademyStaffQuota academyStaffQuota;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long academyId;

    private Long firstAccountId;

    private Long secondAccountId;

    @BeforeEach
    void 관계자가_없는_학원과_후보_계정_2개를_만든다() {
        뒷정리한다();
        Academy academy = academyRepository.save(
                Academy.register(ACADEMY_CODE, "P3T1동시성학원", "제주", null, null));
        academyId = academy.getId();
        firstAccountId = 계정을_만든다("p3t1conc1", "010-0000-4001");
        secondAccountId = 계정을_만든다("p3t1conc2", "010-0000-4002");
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN "
                + "(SELECT id FROM academy WHERE code = ?)", ACADEMY_CODE);
        jdbcTemplate.update("DELETE FROM account WHERE login_id IN ('p3t1conc1', 'p3t1conc2')");
        jdbcTemplate.update("DELETE FROM academy WHERE code = ?", ACADEMY_CODE);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 STAFF_QUOTA_EXCEEDED} 다.
     *
     * <p>실패한 쪽이 선검사에서 걸렸는지 DB 에서 걸렸는지는 타이밍이 정하며 여기서 가리지 않는다 —
     * <b>어느 쪽이든 같은 코드로 나가야 한다</b> 는 것이 이 단언의 내용이다. DB 경로 자체는
     * {@code AcademyStaffQuotaTest.선검사를_지난_뒤_DB_가_거부해도_409_STAFF_QUOTA_EXCEEDED_로_옮겨진다()}
     * 가 타이밍 없이 결정적으로 고정한다.
     */
    @Test
    void 같은_학원에_동시에_두_요청이_들어오면_성공_1건_실패_1건이다() throws Exception {
        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 승인한다(firstAccountId, 먼저_들어갔다));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 승인한다(secondAccountId, null);
            });

            List<Optional<ErrorCode>> results = List.of(
                    먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), 나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertThat(results.stream().filter(Optional::isEmpty).count())
                    .as("성공은 정확히 1건이어야 한다 — 2건이면 정원이 샌 것이고 0건이면 정상 승인까지 막힌 것이다")
                    .isEqualTo(1);
            assertThat(results.stream().flatMap(Optional::stream).toList())
                    .as("실패는 500 이 아니라 409 STAFF_QUOTA_EXCEEDED 여야 한다")
                    .containsExactly(ErrorCode.STAFF_QUOTA_EXCEEDED);
            assertThat(academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE))
                    .as("응답과 무관하게 DB 에 남은 재직자도 1명이어야 한다")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 한 트랜잭션 안에서 정원 판정을 통과시켜 관계자 행을 넣는다 — T2 의 {@code §6.5} 승인이 할 일과 같다.
     *
     * @param 들어갔음 INSERT 를 보낸 직후 내리는 신호. {@code null} 이면 신호를 내지 않고, 신호를 내는
     *               쪽은 상대가 같은 창에 들어오도록 커밋을 잠시 미룬다
     * @return 성공이면 빈 값, 정원 위반이면 그 에러 코드
     */
    private Optional<ErrorCode> 승인한다(Long accountId, CountDownLatch 들어갔음) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                academyStaffQuota.enforce(academyId,
                        () -> academyStaffRepository.save(AcademyStaff.uponApproval(academyId, accountId)));
                if (들어갔음 != null) {
                    들어갔음.countDown();
                    잠시_커밋을_미룬다();
                }
                return Optional.<ErrorCode>empty();
            });
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    private void 잠시_커밋을_미룬다() {
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Long 계정을_만든다(String loginId, String phone) {
        return accountRepository.save(
                Account.forSignup(academyId, loginId, "x", "동시성" + loginId, phone, null, Role.STAFF)).getId();
    }
}
