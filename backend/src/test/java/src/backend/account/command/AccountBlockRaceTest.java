package src.backend.account.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.command.StaffAccountCommandService;
import src.backend.academy.dto.StaffAccountUpdateRequest;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.manager.event.ManagerRoleChangedEvent;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * BR-249 — 승인이 계정을 잠그지 않고 읽어 전 컬럼을 덮어쓰면, 그 사이 커밋된 로그인 실패 5회 차단(BR-026)이 조용히
 * 지워지던 결함의 재현본이다.
 *
 * <p>경합 창은 승인이 계정을 읽은 <b>직후</b> 훅에서 다른 스레드의 5번째 로그인 실패를 실행해 강제한다. 승인이 계정 행을
 * 잠그고 읽으면 그 로그인은 승인이 커밋할 때까지 기다리므로(훅이 시간 만료로 풀린다) 승인 뒤에 차단이 걸린다.
 *
 * <p>BR-335 — 같은 경합이 승인 밖의 형제 경로(관계자 계정 수정 §6.7 · 매니저 역할 동기 리스너)에도 있었다. 두 경로 모두
 * 계정을 읽은 직후 5번째 실패를 끼워 넣어 같은 단언으로 본다({@link #fifthFailureAfterFirstRead}).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class AccountBlockRaceTest {

    private static final String ACADEMY_NAME = "계정경합시험학원";

    private static final String PASSWORD = "right-password";

    private static final long LOGIN_WAIT_SECONDS = 3;

    private static final long WAIT_LIMIT_SECONDS = 60;

    @Autowired
    private SignupDecision signupDecision;

    @Autowired
    private StaffAccountCommandService staffAccountCommandService;

    @Autowired
    private ManagerRoleAccountSyncListener managerRoleAccountSyncListener;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private LoginCommandService loginCommandService;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SignupRequestRepository signupRequestRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        RepositoryReadHooks.clear();
        cleanUpMarkedRows();
    }

    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + ACADEMY_NAME + "')";
        String accountIds = "(SELECT id FROM account WHERE academy_id IN " + academyIds + ")";
        jdbcTemplate.update("DELETE FROM notification_log WHERE recipient_account_id IN " + accountIds);
        jdbcTemplate.update("DELETE FROM audit_log WHERE actor_account_id IN " + accountIds);
        jdbcTemplate.update("DELETE FROM refresh_token WHERE account_id IN " + accountIds);
        jdbcTemplate.update("DELETE FROM signup_request WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("BR-249 — 승인이 계정을 읽은 뒤 5번째 로그인 실패가 커밋돼도 승인 커밋이 차단을 지우지 않는다")
    void 승인_커밋이_막_걸린_차단을_지우지_않는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("BR249-" + System.nanoTime(), ACADEMY_NAME, "서울",
                null, null));
        String loginId = "br249-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(PASSWORD), "대기계정", "010-0000-0000", null, Role.PARENT));
        SignupRequest request = signupRequestRepository.save(SignupRequest.uponSubmission(academy.getId(),
                account.getId(), Role.PARENT, ApproverType.STAFF, OffsetDateTime.now()));
        // 4번 실패한 대기 계정 — 다음 실패가 차단이다.
        jdbcTemplate.update("UPDATE account SET failed_attempts = 4 WHERE id = ?", account.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicBoolean armed = new AtomicBoolean(false);
        try {
            // 승인이 계정을 읽은 직후 — 다른 스레드가 5번째 실패를 커밋하려 한다. 승인이 계정 행을 잠갔다면 그 로그인은 기다린다.
            Runnable fifthFailureAfterAccountRead = () -> {
                if (!armed.compareAndSet(true, false)) {
                    return;
                }
                Future<?> fifthFailure = pool.submit(() -> {
                    try {
                        loginCommandService.login(loginId, "wrong-password", "127.0.0.1");
                    } catch (BusinessException expected) {
                        // 5번째 실패는 AUTH_ACCOUNT_BLOCKED 로 끝난다.
                    }
                });
                try {
                    fifthFailure.get(LOGIN_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException 승인이_계정을_잠그고_있다) {
                    // 승인 커밋 뒤에 마저 진행한다.
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            // 계정을 잠그지 않고 읽는 옛 경로(findById)와 잠그고 읽는 경로(findByIdForUpdate) 어느 쪽이든 같은 자리에서 끼워 넣는다.
            RepositoryReadHooks.afterRead("findById", fifthFailureAfterAccountRead);
            RepositoryReadHooks.afterRead("findByIdForUpdate", fifthFailureAfterAccountRead);

            Future<?> approval = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                SignupRequest pending = signupRequestRepository.findById(request.getId()).orElseThrow();
                armed.set(true);
                return signupDecision.close(pending, new Decision(true, null), 1L, target -> { });
            }));
            approval.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class,
                account.getId()))
                .as("5번째 실패가 커밋된 뒤 승인이 옛 스냅샷으로 전 컬럼을 덮어쓰면 active 로 돌아가 차단이 사라진다")
                .isEqualTo("blocked");
    }

    @Test
    @DisplayName("BR-335 — 관계자 계정 수정이 계정을 읽은 뒤 5번째 로그인 실패가 커밋돼도 수정 커밋이 차단을 지우지 않는다")
    void 관계자_계정_수정이_막_걸린_차단을_지우지_않는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("BR335A-" + System.nanoTime(), ACADEMY_NAME, "서울",
                null, null));
        String loginId = "br335a-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(PASSWORD), "관계자", "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academy.getId(), account.getId()));
        jdbcTemplate.update("UPDATE account SET status = 'active', failed_attempts = 4 WHERE id = ?", account.getId());

        fifthFailureAfterFirstRead(loginId, () -> staffAccountCommandService.update(account.getId(),
                new StaffAccountUpdateRequest("새이름", null, null, null, null)));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM account WHERE id = ?", String.class, account.getId()))
                .as("수정 자체는 반영된다").isEqualTo("새이름");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class,
                account.getId()))
                .as("5번째 실패가 커밋된 뒤 수정이 옛 스냅샷으로 전 컬럼을 덮어쓰면 active 로 돌아가 차단이 사라진다")
                .isEqualTo("blocked");
    }

    @Test
    @DisplayName("BR-335 — 매니저 역할 동기가 계정을 읽은 뒤 5번째 로그인 실패가 커밋돼도 역할 변경 커밋이 차단을 지우지 않는다")
    void 매니저_역할_동기가_막_걸린_차단을_지우지_않는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("BR335B-" + System.nanoTime(), ACADEMY_NAME, "서울",
                null, null));
        String loginId = "br335b-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(PASSWORD), "동승자", "010-0000-0000", null, Role.ESCORT));
        jdbcTemplate.update("UPDATE account SET status = 'active', failed_attempts = 4 WHERE id = ?", account.getId());

        fifthFailureAfterFirstRead(loginId, () -> managerRoleAccountSyncListener
                .syncRole(new ManagerRoleChangedEvent(account.getId(), ManagerRole.DRIVER)));

        assertThat(jdbcTemplate.queryForObject("SELECT role FROM account WHERE id = ?", String.class, account.getId()))
                .as("역할 변경 자체는 반영된다").isEqualTo("driver");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class,
                account.getId()))
                .as("5번째 실패가 커밋된 뒤 역할 변경이 옛 스냅샷으로 전 컬럼을 덮어쓰면 차단이 사라진다")
                .isEqualTo("blocked");
    }

    /**
     * 한 트랜잭션 안에서 {@code operation} 이 계정을 처음 읽은 직후, 다른 스레드가 {@code loginId} 의 5번째 로그인 실패를
     * 커밋하려 하게 한다. 계정 행을 잠그고 읽었다면 그 로그인은 {@code operation} 이 커밋할 때까지 기다린다(시간 만료로 풀린다).
     * 읽기 저장소 메서드 이름 둘({@code findById} · {@code findByIdForUpdate}) 어느 쪽이든 같은 자리에서 끼워 넣는다.
     */
    private void fifthFailureAfterFirstRead(String loginId, Runnable operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicBoolean armed = new AtomicBoolean(false);
        try {
            Runnable fifthFailure = () -> {
                if (!armed.compareAndSet(true, false)) {
                    return;
                }
                Future<?> failure = pool.submit(() -> {
                    try {
                        loginCommandService.login(loginId, "wrong-password", "127.0.0.1");
                    } catch (BusinessException expected) {
                        // 5번째 실패는 AUTH_ACCOUNT_BLOCKED 로 끝난다.
                    }
                });
                try {
                    failure.get(LOGIN_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException 읽은_쪽이_계정을_잠그고_있다) {
                    // 읽은 쪽 커밋 뒤에 마저 진행한다.
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            RepositoryReadHooks.afterRead("findById", fifthFailure);
            RepositoryReadHooks.afterRead("findByIdForUpdate", fifthFailure);

            Future<?> run = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                armed.set(true);
                operation.run();
                return null;
            }));
            run.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
