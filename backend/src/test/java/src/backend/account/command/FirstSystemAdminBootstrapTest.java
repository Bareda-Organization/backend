package src.backend.account.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SystemAdminRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * R46 ops C #3 — 첫 배포의 prod 는 계정이 0개인데 가입 API 로는 메인 관리자를 만들 수 없어, 배포가 성공해도 로그인할 사람이
 * 없었다. 환경변수로 메인 관리자가 하나도 없을 때만 1명을 만드는 기동 러너를 본다.
 *
 * <p>{@code @Transactional} 로 돌려 시험 DB 를 바꾸지 않는다 — 시드에는 이미 메인 관리자가 있으므로 "없는 상태" 는 등록부 행을
 * 이 트랜잭션 안에서 지워 만든다. 러너는 시험이 직접 생성해(환경변수 값을 생성자로 넣어) 부른다.
 */
@SpringBootTest
@Transactional
class FirstSystemAdminBootstrapTest {

    private static final String LOGIN_ID = "first-owner";

    private static final String PASSWORD = "first-owner-pw-1234";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SystemAdminRepository systemAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginCommandService loginCommandService;

    @Autowired
    private Clock clock;

    private ListAppender<ILoggingEvent> logs;

    private Logger runnerLogger;

    @BeforeEach
    void captureRunnerLogs() {
        runnerLogger = (Logger) LoggerFactory.getLogger(FirstSystemAdminBootstrap.class);
        logs = new ListAppender<>();
        logs.start();
        runnerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseRunnerLogs() {
        runnerLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("메인 관리자가 하나도 없으면 환경값으로 1명을 만들고 그 계정으로 로그인된다")
    void createsOneMainAdminWhenNoneExists() throws Exception {
        systemAdminRepository.deleteAll();
        systemAdminRepository.flush();
        String hash = passwordEncoder.encode(PASSWORD);

        runnerWith(LOGIN_ID, hash).run(null);

        Account created = accountRepository.findByLoginId(LOGIN_ID).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.SYSTEM_ADMIN);
        assertThat(created.getStatus()).as("가입 승인을 거치지 않는 발급이라 곧바로 활성").isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getAcademyId()).as("메인 관리자는 학원에 속하지 않는다").isNull();
        assertThat(systemAdminRepository.findAll()).extracting("accountId").containsExactly(created.getId());
        assertThat(loginCommandService.login(LOGIN_ID, PASSWORD, "127.0.0.1").role()).isEqualTo(Role.SYSTEM_ADMIN);
    }

    @Test
    @DisplayName("이미 메인 관리자가 있으면 아무것도 만들지 않는다 — 잘못 남은 환경값이 있어도 멈추지 않는다")
    void doesNothingWhenAMainAdminAlreadyExists() throws Exception {
        long accountsBefore = accountRepository.count();
        long adminsBefore = systemAdminRepository.count();
        assertThat(adminsBefore).as("시드에는 메인 관리자가 있다").isPositive();

        runnerWith(LOGIN_ID, "이건-해시가-아니다").run(null);

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
        assertThat(systemAdminRepository.count()).isEqualTo(adminsBefore);
        assertThat(accountRepository.existsByLoginId(LOGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("해시가 bcrypt 형식이 아니면 만들지 않고 멈춘다 — 예외에 값이 실리지 않는다")
    void refusesAPlainTextPassword() {
        systemAdminRepository.deleteAll();
        long accountsBefore = accountRepository.count();

        assertThatThrownBy(() -> runnerWith(LOGIN_ID, PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bcrypt")
                .hasMessageNotContaining(PASSWORD);

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
    }

    @Test
    @DisplayName("아이디와 해시 중 하나만 있으면 멈춘다")
    void refusesWhenOnlyOneOfTheTwoIsGiven() {
        systemAdminRepository.deleteAll();
        String hash = passwordEncoder.encode(PASSWORD);

        assertThatThrownBy(() -> runnerWith(LOGIN_ID, "").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runnerWith("", hash).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(accountRepository.existsByLoginId(LOGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("이미 다른 계정이 쓰는 아이디면 멈춘다 — 기존 계정을 관리자로 바꾸지 않는다")
    void refusesALoginIdOwnedByAnotherAccount() {
        systemAdminRepository.deleteAll();
        Account existing = accountRepository.findAll().get(0);
        Role roleBefore = existing.getRole();
        String hash = passwordEncoder.encode(PASSWORD);

        assertThatThrownBy(() -> runnerWith(existing.getLoginId(), hash).run(null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(existing.getRole()).isEqualTo(roleBefore);
        assertThat(systemAdminRepository.count()).isZero();
    }

    @Test
    @DisplayName("둘 다 비어 있으면 아무것도 만들지 않고 관리자가 없다는 경고만 남긴다")
    void warnsWhenNothingIsConfiguredAndNoAdminExists() throws Exception {
        systemAdminRepository.deleteAll();
        long accountsBefore = accountRepository.count();

        runnerWith("", "").run(null);

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
        assertThat(messages()).anyMatch(message -> message.contains("BOOTSTRAP_ADMIN_LOGIN_ID"));
    }

    @Test
    @DisplayName("생성·거부 어느 경우에도 로그에 비밀번호와 해시를 남기지 않는다")
    void neverLogsThePasswordOrItsHash() throws Exception {
        systemAdminRepository.deleteAll();
        String hash = passwordEncoder.encode(PASSWORD);
        runnerWith(LOGIN_ID, hash).run(null);
        systemAdminRepository.deleteAll();
        runnerWith("another-owner", PASSWORD).runQuietly();

        assertThat(messages()).as("로그를 잡지 못했다면 이 시험은 아무것도 검사하지 못한다").isNotEmpty()
                .noneMatch(message -> message.contains(hash) || message.contains(PASSWORD));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Runner runnerWith(String loginId, String passwordHash) {
        return new Runner(new FirstSystemAdminBootstrap(accountRepository, systemAdminRepository, clock, loginId,
                passwordHash));
    }

    /** 러너 호출을 짧게 쓰려는 얇은 껍데기 — 거부 예외는 {@link #runQuietly()} 가 삼킨다. */
    private record Runner(FirstSystemAdminBootstrap bootstrap) {

        void run(Object ignored) throws Exception {
            bootstrap.run(null);
        }

        void runQuietly() {
            try {
                bootstrap.run(null);
            } catch (IllegalStateException expectedRefusal) {
                // 거부 경로의 로그를 남기게 하려고 부른다 — 거부 자체는 다른 시험이 본다.
            }
        }
    }
}
