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

    /**
     * 스레드 하나가 상대를 기다리는 상한 — <b>정상 흐름에서는 소진되지 않는다.</b>
     *
     * <p>여기 걸리면 상대가 죽었거나 DB 잠금이 안 풀린 것이라, 그때는 테스트가 매달리는 대신
     * 실패로 드러나야 한다.
     */
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

    /**
     * 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자식부터 부모 순이다(FK RESTRICT).
     *
     * <p>세 삭제를 각각 감싸는 이유는 <b>앞 문장이 실패하면 뒤가 통째로 건너뛰어지기</b> 때문이다.
     * 그러면 부모만 남거나 자식만 남은 상태로 다음 실행이 시작해, 실패 원인이 이 테스트가 아니라
     * 픽스처 준비 쪽으로 옮겨 붙는다. 삭제 실패 자체는 여기서 숨기지 않고 마지막에 다시 던진다.
     */
    @AfterEach
    void 뒷정리한다() {
        RuntimeException 실패 = null;
        for (String 삭제 : List.of(
                "DELETE FROM academy_staff WHERE academy_id IN (SELECT id FROM academy WHERE code = '"
                        + ACADEMY_CODE + "')",
                "DELETE FROM account WHERE login_id IN ('p3t1conc1', 'p3t1conc2')",
                "DELETE FROM academy WHERE code = '" + ACADEMY_CODE + "'")) {
            try {
                jdbcTemplate.update(삭제);
            } catch (RuntimeException e) {
                실패 = e;
            }
        }
        if (실패 != null) {
            throw 실패;
        }
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
        CountDownLatch 나중도_착수했다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 승인한다(firstAccountId, 먼저_들어갔다, 나중도_착수했다));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                나중도_착수했다.countDown();
                return 승인한다(secondAccountId, null, null);
            });

            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다 — 앞의 get() 이
            // 시간 초과로 끊겼다면 스레드가 아직 행을 잡고 있을 수 있다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().filter(Optional::isEmpty).count())
                .as("성공은 정확히 1건이어야 한다 — 2건이면 정원이 샌 것이고 0건이면 정상 승인까지 막힌 것이다")
                .isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("실패는 500 이 아니라 409 STAFF_QUOTA_EXCEEDED 여야 한다")
                .containsExactly(ErrorCode.STAFF_QUOTA_EXCEEDED);
        assertThat(academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE))
                .as("응답과 무관하게 DB 에 남은 재직자도 1명이어야 한다")
                .isEqualTo(1);
    }

    /**
     * 한 트랜잭션 안에서 정원 판정을 통과시켜 관계자 행을 넣는다 — T2 의 {@code §6.5} 승인이 할 일과 같다.
     *
     * <p>두 신호로 겹침을 만든다. 고정 대기({@code Thread.sleep})를 쓰지 않는 이유는 그 값이 기계 속도에
     * 묶여, 느린 기계에서는 겹침이 사라지고 빠른 기계에서는 그만큼 매번 낭비되기 때문이다.
     *
     * @param 들어갔음   INSERT 를 보낸 직후 내리는 신호 — 상대가 이때부터 착수한다
     * @param 상대가_착수 상대가 착수했다는 신호. 이것을 받고서야 커밋한다 — 커밋을 미루는 동안 상대가
     *                  같은 창에 들어온다. {@code null} 이면 기다리지 않고 바로 커밋한다
     * @return 성공이면 빈 값, 정원 위반이면 그 에러 코드
     */
    private Optional<ErrorCode> 승인한다(Long accountId, CountDownLatch 들어갔음, CountDownLatch 상대가_착수) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                academyStaffQuota.enforce(academyId,
                        () -> academyStaffRepository.save(AcademyStaff.uponApproval(academyId, accountId)));
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

    private Long 계정을_만든다(String loginId, String phone) {
        return accountRepository.save(
                Account.forSignup(academyId, loginId, "x", "동시성" + loginId, phone, null, Role.STAFF)).getId();
    }
}
