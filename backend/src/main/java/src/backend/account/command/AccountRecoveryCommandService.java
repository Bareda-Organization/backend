package src.backend.account.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.TemporaryPasswordGenerator;
import src.backend.account.entity.Account;
import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.account.repository.VerificationCodeRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.sms.spec.SmsSender;

/**
 * 전화번호 복구(AUTH-08, API_SPEC §2.9 · Ruling 513) — 문자 발송기({@link SmsSender})가 있을 때만 동작하고,
 * 없으면 아무것도 하지 않고 {@code 503 RECOVERY_UNAVAILABLE} 이다(Ruling 329).
 *
 * <p>두 단계다. <b>발급</b>(코드 없음) — 같은 번호 60초 1회·24시간 5회를 넘지 않았으면 6자리 코드를 저장하고
 * 문자로 보낸다. <b>대조</b>(코드 있음) — 대조 횟수를 조건부 UPDATE 로 올리고(상한 5), 맞으면 코드를 소비한 뒤
 * 임시 비밀번호(또는 아이디)를 <b>문자로만</b> 보낸다. 응답 본문에는 코드·비밀번호·아이디가 실리지 않는다.
 *
 * <p>대상은 학부모·학생·매니저 계정이다 — 관계자·메인 관리자는 문자 한 통(SIM 탈취)으로 학원 전체 권한을 얻게 되므로
 * 메인 관리자 경로(§6.7)로만 초기화한다.
 *
 * <p><b>트랜잭션을 템플릿으로 직접 건다.</b> 틀린 코드의 대조 횟수는 응답이 403 이어도 커밋돼야 상한이 동작한다 —
 * 메서드 전체에 {@code @Transactional} 을 걸고 예외로 거절하면 올린 횟수가 롤백되어 무제한 대조가 된다. 그래서 대조
 * 결과를 값으로 돌려받아 커밋한 뒤에 403 을 던진다. 문자 발송은 트랜잭션의 마지막에 두어, 발송이 실패하면 코드 발급·
 * 비밀번호 교체가 함께 되돌려진다(받지 못한 임시 비밀번호로 계정이 잠기지 않는다).
 *
 * <p>번호·코드·임시 비밀번호를 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AccountRecoveryCommandService {

    /** 문자 복구 대상 역할 — 관계자·메인 관리자는 제외(Ruling 513). */
    private static final Set<Role> RECOVERABLE_ROLES = Set.of(Role.PARENT, Role.STUDENT, Role.DRIVER, Role.ESCORT);

    /** 코드 유효 시간 — 문자로 받아 입력하는 데 충분하고, 유출된 코드가 오래 살아 있지 않게 하는 길이다. */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** 같은 번호 발급 간격(API_SPEC §2.9). */
    private static final Duration ISSUE_INTERVAL = Duration.ofSeconds(60);

    /** 같은 번호의 24시간 발급 상한(API_SPEC §2.9). */
    private static final int DAILY_ISSUE_LIMIT = 5;

    private static final int CODE_DIGITS = 6;

    private final ObjectProvider<SmsSender> smsSender;

    private final AccountRepository accountRepository;

    private final VerificationCodeRepository verificationCodeRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    private final PasswordEncoder passwordEncoder;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    /**
     * 복구 요청 한 건을 처리한다 — {@code verificationCode} 가 없으면 발급, 있으면 대조.
     *
     * @throws BusinessException {@code RECOVERY_UNAVAILABLE}(발송기 없음) · {@code ACCOUNT_NOT_FOUND}(발급 때 미등록
     *                           번호) · {@code RECOVERY_RATE_LIMITED}(발급 빈도 초과) ·
     *                           {@code VERIFICATION_CODE_INVALID}(대조 실패)
     */
    public void recover(String type, String phone, String verificationCode) {
        SmsSender sender = smsSender.getIfAvailable();
        if (sender == null) {
            throw new BusinessException(ErrorCode.RECOVERY_UNAVAILABLE);
        }
        VerificationPurpose purpose = "login_id".equals(type) ? VerificationPurpose.LOGIN_ID : VerificationPurpose.PASSWORD;
        if (verificationCode == null) {
            transactionTemplate.executeWithoutResult(status -> issue(sender, phone, purpose));
            return;
        }
        Boolean passed = transactionTemplate.execute(status -> verifyAndDeliver(sender, phone, purpose, verificationCode));
        if (!Boolean.TRUE.equals(passed)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
    }

    private void issue(SmsSender sender, String phone, VerificationPurpose purpose) {
        // 같은 번호의 계정 행을 잠가 동시 발급을 직렬화한다 — 둘 다 "아직 없음" 을 보고 한도를 넘기지 않게.
        List<Account> accounts = accountRepository.findAllByPhoneAndRoleInForUpdate(phone, RECOVERABLE_ROLES);
        if (accounts.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (verificationCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minus(ISSUE_INTERVAL)) > 0
                || verificationCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minusDays(1)) >= DAILY_ISSUE_LIMIT) {
            throw new BusinessException(ErrorCode.RECOVERY_RATE_LIMITED);
        }
        verificationCodeRepository.invalidateUnconsumedByPhoneAndPurpose(phone, purpose, now);
        String code = newCode();
        verificationCodeRepository.save(VerificationCode.issue(phone, code, purpose, now.plus(CODE_TTL), now));
        sender.send(phone, "[바래다] 인증번호 " + code + " (" + CODE_TTL.toMinutes() + "분 안에 입력)");
    }

    /** 통과하면 {@code true} — 실패는 예외가 아니라 값이다(위 클래스 설명, 대조 횟수를 커밋해야 한다). */
    private boolean verifyAndDeliver(SmsSender sender, String phone, VerificationPurpose purpose, String input) {
        Optional<VerificationCode> latest = verificationCodeRepository.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone,
                purpose);
        if (latest.isEmpty()) {
            return false;
        }
        VerificationCode issued = latest.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 상한 도달·소비·불일치·만료를 같은 값으로 돌려준다 — 가르면 "이 번호로 코드를 발급받은 적이 있다" 를 알려 준다.
        if (verificationCodeRepository.recordAttempt(issued.getId(), VerificationCode.MAX_ATTEMPTS) == 0
                || !MessageDigest.isEqual(issued.getCode().getBytes(StandardCharsets.UTF_8),
                        input.getBytes(StandardCharsets.UTF_8))
                || verificationCodeRepository.consumeIfValid(issued.getId(), now) == 0) {
            return false;
        }
        List<Account> accounts = accountRepository.findAllByPhoneAndRoleInForUpdate(phone, RECOVERABLE_ROLES);
        if (accounts.isEmpty()) {
            return false;
        }
        String text = purpose == VerificationPurpose.LOGIN_ID ? loginIdText(accounts) : resetPasswords(accounts, now);
        sender.send(phone, text);
        return true;
    }

    private static String loginIdText(List<Account> accounts) {
        return "[바래다] 아이디 " + accounts.stream().map(Account::getLoginId).collect(Collectors.joining(", "));
    }

    /**
     * 같은 번호의 계정마다 임시 비밀번호로 바꾸고 문자 본문을 만든다. refresh 토큰 무효화는 영속성 컨텍스트를 비우므로
     * 비밀번호 변경이 전부 끝난 뒤에 한다({@code AccountPasswordResetCommandService} 와 같은 순서).
     */
    private String resetPasswords(List<Account> accounts, OffsetDateTime now) {
        StringBuilder text = new StringBuilder("[바래다]");
        for (Account account : accounts) {
            String temporaryPassword = temporaryPasswordGenerator.generate();
            account.changePassword(passwordEncoder.encode(temporaryPassword));
            text.append(" 아이디 ").append(account.getLoginId()).append(" 임시 비밀번호 ").append(temporaryPassword)
                    .append(" /");
        }
        text.append(" 로그인 뒤 바로 바꿔 주세요");
        for (Account account : accounts) {
            refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
        }
        return text.toString();
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < CODE_DIGITS; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }
}
