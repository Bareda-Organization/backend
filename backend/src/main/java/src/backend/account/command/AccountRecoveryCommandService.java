package src.backend.account.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
 * 문자로 보낸다. <b>번호가 가입돼 있는지는 응답으로 알 수 없다</b>(Ruling 553) — 미등록 번호도 같은 {@code 200}·같은 한도를
 * 받고 문자만 나가지 않으며, 대조 실패는 이유를 가르지 않는 같은 {@code 403} 이다. <b>대조</b>(코드 있음) — 대조 횟수를 조건부 UPDATE 로 올리고(상한 5), 맞으면 코드를 소비한 뒤
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
 * <p><b>임시 비밀번호의 BCrypt 는 트랜잭션·행 잠금 밖에서 미리 만든다</b>(R46 T-3) — 발급된 코드와 입력이 맞을 때만, 같은 번호의
 * 계정 수만큼 만들어 둔다(틀린 코드로 BCrypt 를 태우지 못하게). 대조·소비·교체·문자 발송은 그대로 한 트랜잭션이고, 그
 * 사이 계정이 늘어 미리 만든 해시가 모자라면 모자란 만큼만 안에서 만든다.
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

    /** 하루가 지난 발급 행을 지우는 간격 — 매 요청마다 훑지 않는다. */
    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(10);

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

    /** 마지막으로 하루 지난 발급 행을 지운 시각 — {@link #purgeStaleCodes}. */
    private final AtomicReference<Instant> lastPurgeAt = new AtomicReference<>(Instant.EPOCH);

    /**
     * 복구 요청 한 건을 처리한다 — {@code verificationCode} 가 없으면 발급, 있으면 대조.
     *
     * @throws BusinessException {@code RECOVERY_UNAVAILABLE}(발송기 없음) · {@code RECOVERY_RATE_LIMITED}(발급 빈도
     *                           초과 — 가입 여부와 무관) · {@code VERIFICATION_CODE_INVALID}(대조 실패)
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
        Map<Long, TemporaryPassword> prepared = purpose == VerificationPurpose.PASSWORD
                && matchesIssuedCode(phone, purpose, verificationCode) ? prepareTemporaryPasswords(phone) : Map.of();
        Boolean passed = transactionTemplate
                .execute(status -> verifyAndDeliver(sender, phone, purpose, verificationCode, prepared));
        if (!Boolean.TRUE.equals(passed)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
    }

    private void issue(SmsSender sender, String phone, VerificationPurpose purpose) {
        // 이 번호의 발급을 직렬화한다 — 계정 행 잠금은 미등록 번호에 잠글 행이 없어, 동시 요청의 통과 수가 가입 여부에 따라 갈린다.
        // 발급은 계정이 있는지만 보고 바꾸지 않으므로 행을 잠그지 않는다 — 잠그면 문자 업체 응답이 올 때까지 그 계정의 로그인이 선다(BR-308).
        verificationCodeRepository.lockByPhone(phone);
        List<Account> accounts = accountRepository.findAllByPhoneAndRoleIn(phone, RECOVERABLE_ROLES);
        OffsetDateTime now = OffsetDateTime.now(clock);
        purgeStaleCodes(now);
        // 가입 여부와 무관하게 같은 길을 지난다(Ruling 553) — 미등록 번호도 같은 한도를 받고 발급 행을 남기며, 다른 것은
        // 문자를 보내는지뿐이다. 미등록·대상 밖 역할이 다른 응답(404 · 한도 없음)을 내면 번호의 가입 여부가 드러난다.
        if (verificationCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minus(ISSUE_INTERVAL)) > 0
                || verificationCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minusDays(1)) >= DAILY_ISSUE_LIMIT) {
            throw new BusinessException(ErrorCode.RECOVERY_RATE_LIMITED);
        }
        verificationCodeRepository.invalidateUnconsumedByPhoneAndPurpose(phone, purpose, now);
        String code = newCode();
        verificationCodeRepository.save(VerificationCode.issue(phone, code, purpose, now.plus(CODE_TTL), now));
        if (!accounts.isEmpty()) {
            sender.send(phone, "[바래다] 인증번호 " + code + " (" + CODE_TTL.toMinutes() + "분 안에 입력)");
        }
    }

    /**
     * 하루가 지난 발급 행을 지운다 — 미등록 번호의 요청도 행을 남기므로 번호를 바꿔 가며 보내는 요청이 행을 쌓지 못하게
     * 한다. 매 요청마다 훑지 않고 {@link #PURGE_INTERVAL} 에 한 번만 돈다.
     *
     * <p>ponytail: 마지막 정리 시각을 메모리에 둔다 — 백엔드가 인스턴스 1개라는 배포 전제(CLAUDE.md)를 따르고, 재기동 직후
     * 한 번 더 도는 것은 해가 없다. 여러 인스턴스가 되면 스케줄러(보존 정리)로 옮긴다.
     */
    private void purgeStaleCodes(OffsetDateTime now) {
        Instant last = lastPurgeAt.get();
        Instant current = now.toInstant();
        if (last.plus(PURGE_INTERVAL).isAfter(current) || !lastPurgeAt.compareAndSet(last, current)) {
            return;
        }
        verificationCodeRepository.deleteCreatedBefore(now.minusDays(1));
    }

    /** 임시 비밀번호 원문과 저장할 해시 — 해시는 트랜잭션 밖에서 미리 만든다. */
    private record TemporaryPassword(String plain, String hash) {
    }

    /** 가장 최근에 발급된 코드와 입력이 같고 아직 쓸 수 있는지 잠금 없이 본다 — 횟수 기록·소비는 하지 않는다(그건 대조 트랜잭션이 한다). */
    private boolean matchesIssuedCode(String phone, VerificationPurpose purpose, String input) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return verificationCodeRepository.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .filter(issued -> issued.getConsumedAt() == null && issued.getExpiresAt().isAfter(now))
                .filter(issued -> MessageDigest.isEqual(issued.getCode().getBytes(StandardCharsets.UTF_8),
                        input.getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }

    /** 같은 번호의 복구 대상 계정마다 임시 비밀번호와 해시를 만든다 — 계정 id 로 찾아 쓴다. */
    private Map<Long, TemporaryPassword> prepareTemporaryPasswords(String phone) {
        return accountRepository.findAllByPhoneAndRoleIn(phone, RECOVERABLE_ROLES).stream()
                .collect(Collectors.toMap(Account::getId, account -> issueTemporaryPassword()));
    }

    private TemporaryPassword issueTemporaryPassword() {
        String plain = temporaryPasswordGenerator.generate();
        return new TemporaryPassword(plain, passwordEncoder.encode(plain));
    }

    /** 통과하면 {@code true} — 실패는 예외가 아니라 값이다(위 클래스 설명, 대조 횟수를 커밋해야 한다). */
    private boolean verifyAndDeliver(SmsSender sender, String phone, VerificationPurpose purpose, String input,
            Map<Long, TemporaryPassword> prepared) {
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
        String text = purpose == VerificationPurpose.LOGIN_ID ? loginIdText(accounts)
                : resetPasswords(accounts, now, prepared);
        sender.send(phone, text);
        return true;
    }

    private static String loginIdText(List<Account> accounts) {
        return "[바래다] 아이디 " + accounts.stream().map(Account::getLoginId).collect(Collectors.joining(", "));
    }

    /**
     * 같은 번호의 계정마다 임시 비밀번호로 바꾸고 문자 본문을 만든다. 문자함에 평문으로 남는 값이라 강제 변경 표식을 켠다
     * ({@link Account#issueTemporaryPassword} · Ruling 785). refresh 토큰 무효화는 영속성 컨텍스트를 비우므로
     * 비밀번호 변경이 전부 끝난 뒤에 한다({@code AccountPasswordResetCommandService} 와 같은 순서).
     */
    private String resetPasswords(List<Account> accounts, OffsetDateTime now, Map<Long, TemporaryPassword> prepared) {
        StringBuilder text = new StringBuilder("[바래다]");
        for (Account account : accounts) {
            TemporaryPassword temporary = prepared.containsKey(account.getId()) ? prepared.get(account.getId())
                    : issueTemporaryPassword();
            account.issueTemporaryPassword(temporary.hash());
            text.append(" 아이디 ").append(account.getLoginId()).append(" 임시 비밀번호 ").append(temporary.plain())
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
