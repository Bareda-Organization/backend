package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import src.backend.academy.command.StaffAccountCommandService;
import src.backend.academy.dto.StaffAccountUpdateRequest;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.command.AccountPasswordResetCommandService;
import src.backend.account.command.AccountRecoveryCommandService;
import src.backend.account.command.PasswordChangeCommandService;
import src.backend.account.command.SignupCommandService;
import src.backend.account.dto.SignupRequestPayload;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 비밀번호 해시(BCrypt)를 만들거나 대조하는 순간 이 스레드에 트랜잭션·연결·행 잠금이 없다(R46 T-3) — BCrypt 는 요청당
 * 수십~수백 ms 라 잠금 트랜잭션 안에 두면 그 시간만큼 연결이 묶인다. 가입 · 비밀번호 변경 · 관계자 계정 수정 ·
 * 관리자 비밀번호 초기화 · 전화번호 복구 5곳을 {@link PasswordEncoder} 감시로 확인한다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 시험이 트랜잭션을 열면 서비스가 합류해 "밖" 이 존재할 수 없다. 그래서
 * 만든 행을 {@link #뒷정리한다()} 가 직접 지운다({@link AccountRecoveryFlowTest} 와 같은 형태).
 */
@SpringBootTest
@Import(AccountRecoveryFlowTest.RecordingSmsConfig.class)
class PasswordHashingOutsideTransactionTest {

    private static final String OLD_PASSWORD = "old-password-1234!";

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private SignupCommandService signupCommandService;

    @Autowired
    private PasswordChangeCommandService passwordChangeCommandService;

    @Autowired
    private AccountPasswordResetCommandService accountPasswordResetCommandService;

    @Autowired
    private StaffAccountCommandService staffAccountCommandService;

    @Autowired
    private AccountRecoveryCommandService accountRecoveryCommandService;

    private final List<TxObservation> observed = new ArrayList<>();

    private String suffix;

    private Long academyId;

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000));
        academyId = academyRepository.save(Academy.register("R46H" + suffix, "해시시험학원" + suffix, "서울", null, null))
                .getId();
        observed.clear();
        Mockito.doAnswer(invocation -> {
            observed.add(TxObservation.now());
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
        Mockito.doAnswer(invocation -> {
            observed.add(TxObservation.now());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(CharSequence.class), anyString());
    }

    /** FK 순서: refresh_token → academy_staff → account → academy (감사 행은 계정·학원에 FK 가 없어 학원 id 로 지운다). */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM verification_code WHERE phone LIKE ?", "010-" + suffix + "%");
        jdbcTemplate.update("DELETE FROM refresh_token WHERE account_id IN (SELECT id FROM account WHERE academy_id = ?)",
                academyId);
        jdbcTemplate.update("DELETE FROM audit_log WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM signup_request WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
    }

    @Test
    @DisplayName("가입 — BCrypt 인코딩이 트랜잭션 밖에서 돈다")
    void 가입은_BCrypt_인코딩이_트랜잭션_밖에서_돈다() {
        signupCommandService.signup(new SignupRequestPayload("parent", "r46s" + suffix, "new-password-1234!", "가입시험",
                "010-" + suffix, String.valueOf(academyId)));

        assertOutside("가입");
        assertThat(accountRepository.existsByLoginId("r46s" + suffix)).as("가입 계정이 실제로 저장돼야 한다").isTrue();
    }

    @Test
    @DisplayName("비밀번호 변경 — 현재 비밀번호 대조와 새 해시 인코딩이 트랜잭션 밖에서 돈다")
    void 비밀번호_변경은_대조와_인코딩이_트랜잭션_밖에서_돈다() {
        Account account = createAccount("r46c" + suffix, Role.PARENT, "010-" + suffix);

        passwordChangeCommandService.changePassword(account.getId(), OLD_PASSWORD, "changed-password-1234!");

        assertOutside("비밀번호 변경");
        assertThat(passwordEncoder.matches("changed-password-1234!", storedHash(account.getId()))).isTrue();
    }

    @Test
    @DisplayName("비밀번호 변경 — 대조 뒤 잠금 전에 해시가 바뀌었으면 잠근 행 기준으로 다시 대조해 거절한다")
    void 비밀번호_변경은_대조_뒤_해시가_바뀌었으면_거절한다() {
        Account account = createAccount("r46r" + suffix, Role.PARENT, "010-" + suffix);
        String racedHash = new BCryptPasswordEncoder().encode("raced-password-1234!");
        AtomicBoolean raced = new AtomicBoolean();
        // 첫 대조가 끝난 직후 다른 요청이 비밀번호를 바꾼 상황 — 잠금 밖 대조 결과는 옛 해시 기준이라 믿을 수 없다
        Mockito.doAnswer(invocation -> {
            Object matched = invocation.callRealMethod();
            if (raced.compareAndSet(false, true)) {
                jdbcTemplate.update("UPDATE account SET password_hash = ? WHERE id = ?", racedHash, account.getId());
            }
            return matched;
        }).when(passwordEncoder).matches(any(CharSequence.class), anyString());

        assertThatThrownBy(() -> passwordChangeCommandService.changePassword(account.getId(), OLD_PASSWORD,
                "changed-password-1234!"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        assertThat(storedHash(account.getId())).as("그 사이 바뀐 해시를 새 비밀번호가 덮어쓰면 안 된다").isEqualTo(racedHash);
    }

    @Test
    @DisplayName("관리자 비밀번호 초기화 — 임시 비밀번호 인코딩이 트랜잭션 밖에서 돈다")
    void 관리자_초기화는_인코딩이_트랜잭션_밖에서_돈다() {
        Account actor = createAccount("r46a" + suffix, Role.STAFF, "010-1" + suffix);
        Account target = createAccount("r46t" + suffix, Role.PARENT, "010-" + suffix);

        accountPasswordResetCommandService.reset(new AuthUser(actor.getId(), academyId, Role.STAFF, AccountStatus.ACTIVE,
                false), target.getId());

        assertOutside("관리자 초기화");
        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedHash(target.getId()))).as("비밀번호가 실제로 바뀌어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("관계자 계정 수정(비밀번호 초기화) — 임시 비밀번호 인코딩이 트랜잭션 밖에서 돈다")
    void 관계자_계정_수정은_인코딩이_트랜잭션_밖에서_돈다() {
        Account staff = createAccount("r46f" + suffix, Role.STAFF, "010-" + suffix);
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, staff.getId()));

        staffAccountCommandService.update(staff.getId(), new StaffAccountUpdateRequest(null, null, null, true, null));

        assertOutside("관계자 계정 수정");
        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedHash(staff.getId()))).as("비밀번호가 실제로 바뀌어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("전화번호 복구 — 임시 비밀번호 인코딩이 트랜잭션 밖에서 돈다")
    void 복구는_인코딩이_트랜잭션_밖에서_돈다() {
        String phone = "010-" + suffix;
        Account account = createAccount("r46v" + suffix, Role.PARENT, phone);

        accountRecoveryCommandService.recover("password", phone, null);
        String code = jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY id DESC LIMIT 1", String.class, phone);
        accountRecoveryCommandService.recover("password", phone, code);

        assertOutside("복구");
        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedHash(account.getId()))).as("비밀번호가 실제로 바뀌어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("전화번호 복구 — 틀린 코드로는 임시 비밀번호 BCrypt 를 만들지 않는다")
    void 복구는_틀린_코드로는_BCrypt_를_만들지_않는다() {
        String phone = "010-" + suffix;
        createAccount("r46w" + suffix, Role.PARENT, phone);
        accountRecoveryCommandService.recover("password", phone, null);
        observed.clear();

        assertThatThrownBy(() -> accountRecoveryCommandService.recover("password", phone, "not-the-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID));

        assertThat(observed).as("코드가 틀리면 계정 수만큼 BCrypt 를 태우는 증폭이 없어야 한다").isEmpty();
    }

    private void assertOutside(String flow) {
        assertThat(observed).as(flow + " — BCrypt 가 실제로 불려야 한다").isNotEmpty();
        assertThat(observed).as(flow + " — BCrypt 호출 시점에 트랜잭션·연결·EntityManager 가 없어야 한다")
                .allSatisfy(observation -> assertThat(observation.holdsNothing()).isTrue());
    }

    private Account createAccount(String loginId, Role role, String phone) {
        return accountRepository.save(Account.forSignup(academyId, loginId,
                new BCryptPasswordEncoder().encode(OLD_PASSWORD), "해시시험", phone, null, role));
    }

    private String storedHash(Long accountId) {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM account WHERE id = ?", String.class, accountId);
    }

    /** 호출 시점에 이 스레드에 묶인 트랜잭션 자원 — {@code resources} 가 비어야 연결·EntityManager 가 없다. */
    private record TxObservation(boolean transactionActive, Map<Object, Object> resources) {

        static TxObservation now() {
            return new TxObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                    Map.copyOf(TransactionSynchronizationManager.getResourceMap()));
        }

        boolean holdsNothing() {
            return !transactionActive && resources.isEmpty();
        }
    }
}
