package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.Account;
import src.backend.account.entity.SystemAdmin;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SystemAdminRepository;
import src.backend.global.common.enums.Role;

/**
 * 메인 관리자가 하나도 없으면 환경변수 값으로 1명을 만든다 — prod 는 계정이 0개이고 가입 신청 5종에 메인 관리자가 없어,
 * 배포가 성공해도 로그인할 사람이 없기 때문이다.
 *
 * <p>이미 메인 관리자가 있으면 값을 읽지도 검증하지도 않는다 — 첫 배포 뒤 SSM 에 남은 값이 이후 배포·롤백을 막으면 안 된다.
 * 인스턴스가 1개라(replicas 금지) 동시 생성 경합은 없고, 있더라도 아이디·등록부 UNIQUE 제약이 한쪽을 거절한다.
 */
@Component
public class FirstSystemAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstSystemAdminBootstrap.class);

    /** bcrypt 해시 — {@code $2a|b|y$} + 비용 2자리 + 솔트·해시 53자. 평문 비밀번호를 넣은 실수를 걸러낸다. */
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private static final String ADMIN_NAME = "메인 관리자";

    /** 연락처 컬럼이 NOT NULL 이라 채우는 값 — 첫 관리자는 로그인 뒤 스스로 바꾼다. */
    private static final String ADMIN_PHONE_PLACEHOLDER = "미등록";

    private final AccountRepository accountRepository;

    private final SystemAdminRepository systemAdminRepository;

    private final Clock clock;

    private final String loginId;

    private final String passwordHash;

    public FirstSystemAdminBootstrap(AccountRepository accountRepository, SystemAdminRepository systemAdminRepository,
            Clock clock, @Value("${app.bootstrap-admin.login-id:}") String loginId,
            @Value("${app.bootstrap-admin.password-hash:}") String passwordHash) {
        this.accountRepository = accountRepository;
        this.systemAdminRepository = systemAdminRepository;
        this.clock = clock;
        this.loginId = loginId.strip();
        this.passwordHash = passwordHash.strip();
    }

    /**
     * 메인 관리자가 없을 때만 환경값으로 활성 계정 1개와 등록부 행을 함께 만든다. 값이 잘못됐으면 아무것도 만들지 않고
     * 기동을 멈춘다 — 조용히 넘기면 배포는 성공하는데 로그인할 사람이 없는 상태로 뜬다.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (systemAdminRepository.count() > 0) {
            return;
        }
        if (loginId.isEmpty() && passwordHash.isEmpty()) {
            log.warn("메인 관리자가 없는데 BOOTSTRAP_ADMIN_LOGIN_ID · BOOTSTRAP_ADMIN_PASSWORD_HASH 가 비어 있다 — 로그인할 수 있는 관리자가 없다");
            return;
        }
        assertConfigurationUsable();

        // 가입 신청·승인 절차를 거치지 않는 발급이라 신청 팩토리로 만든 뒤 곧바로 승인 상태로 둔다.
        Account account = Account.forSignup(null, loginId, passwordHash, ADMIN_NAME, ADMIN_PHONE_PLACEHOLDER, null,
                Role.SYSTEM_ADMIN);
        account.approveSignup();
        Account saved = accountRepository.save(account);
        systemAdminRepository.save(SystemAdmin.uponGrant(saved.getId(), OffsetDateTime.now(clock)));
        log.info("첫 메인 관리자 계정을 만들었다(accountId={})", saved.getId());
    }

    /** 값의 쌍·형식·아이디 충돌을 확인한다 — 예외 메시지에는 값 자체를 싣지 않는다(평문 비밀번호일 수 있다). */
    private void assertConfigurationUsable() {
        if (loginId.isEmpty() || passwordHash.isEmpty()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_LOGIN_ID 와 BOOTSTRAP_ADMIN_PASSWORD_HASH 는 함께 있어야 한다");
        }
        if (!BCRYPT_HASH.matcher(passwordHash).matches()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD_HASH 가 bcrypt 해시 형식이 아니다 — 평문 비밀번호를 넣지 않았는지 확인할 것");
        }
        if (accountRepository.existsByLoginId(loginId)) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_LOGIN_ID 를 이미 다른 계정이 쓰고 있다 — 다른 아이디로 바꿀 것");
        }
    }
}
