package src.backend.account.command;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import src.backend.account.dto.RecoverResponse;
import src.backend.account.entity.Account;
import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.account.repository.VerificationCodeRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 아이디·비밀번호 복구(AUTH-08, API_SPEC §2.9) — {@code verification_code} 유무로 "코드 발송"과
 * "코드 대조 후 복구"를 가른다. **응답 스키마가 API_SPEC 에 규정되지 않아, 세 결과 형태를 Task 4
 * 가 직접 설계했다**(보고서 ⑥) — SMS 발송 연동이 없어 코드는 이 서비스가 생성해 DB 에 남기고,
 * 비밀번호 복구는 발급한 임시 비밀번호를 응답 본문에 그대로 실어 돌려준다(별도 통지 채널 부재).
 */
@Service
public class RecoverCommandService {

    /** SMS 인증 코드 유효기간(Task 4 판단 — API_SPEC 미규정, 보고서 ⑥). */
    private static final long CODE_VALIDITY_MINUTES = 5;
    private static final int CODE_LENGTH = 6;
    private static final int TEMP_PASSWORD_LENGTH = 10;
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AccountRepository accountRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RecoverCommandService(AccountRepository accountRepository,
            VerificationCodeRepository verificationCodeRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, Clock clock) {
        this.accountRepository = accountRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * {@code noRollbackFor} 가 이 경로의 안전장치다 — 코드 대조에 실패하면
     * {@code VERIFICATION_CODE_INVALID} 를 던지는데, 기본 규칙대로 롤백하면 방금 올린
     * {@code attempt_count} 가 함께 사라져 상한이 영원히 도달하지 않는다. 그러면 공격자는 6자리
     * 코드를 무제한 대조할 수 있고, 성공 즉시 응답 본문의 임시 비밀번호로 계정을 가져간다
     * (리뷰 라운드 1 C1). 커밋을 유지해야 누적이 남는다.
     *
     * <p>이 메서드가 던지는 {@link BusinessException} 중 나머지 셋({@code VALIDATION_FAILED} ·
     * {@code ACCOUNT_NOT_FOUND} · 코드 부재)은 전부 쓰기 이전 지점에서 나므로 커밋해도 남는 변경이
     * 부재하다. <b>이 메서드에 쓰기를 추가하는 사람은 그 전제가 유지되는지 먼저 확인해야 한다</b> —
     * 확인 없이 얹으면 실패 응답을 받은 요청이 절반만 반영된 상태로 커밋된다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RecoverResponse recover(String rawType, String phone, String submittedCode) {
        VerificationPurpose purpose = parsePurpose(rawType);
        Account account = accountRepository.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (!StringUtils.hasText(submittedCode)) {
            return sendCode(phone, purpose, now);
        }
        return verifyAndRecover(account, phone, purpose, submittedCode, now);
    }

    /**
     * 인증 코드 미전달 = 발송 요청(API_SPEC §2.9) — 새 코드를 발급해 저장한다.
     *
     * <p>저장 전에 같은 연락처·목적의 미소비 코드를 전부 무효화한다 — 재발급으로 대조 상한을
     * 우회하는 경로를 막는 조치이며, 상한 자체({@code VerificationCode.MAX_ATTEMPTS})의 성립 조건이다
     * (리뷰 라운드 1 C1-④).
     */
    private RecoverResponse sendCode(String phone, VerificationPurpose purpose, OffsetDateTime now) {
        verificationCodeRepository.invalidateUnconsumedByPhoneAndPurpose(phone, purpose, now);
        String code = generateNumericCode();
        verificationCodeRepository.save(
                VerificationCode.issue(phone, code, purpose, now.plusMinutes(CODE_VALIDITY_MINUTES), now));
        return RecoverResponse.ofCodeSent();
    }

    /**
     * 코드 대조 실패도 {@code attempt_count} 를 남겨야 하므로, 판정은 예외가 아니라
     * {@link VerificationCode#verify} 의 반환값으로 받는다 — 던지는 자리는 여기 한 곳이고, 그
     * 시점에는 누적이 이미 엔티티에 반영돼 있다({@link #recover} 의 {@code noRollbackFor} 가 그
     * 반영분을 커밋한다).
     */
    private RecoverResponse verifyAndRecover(Account account, String phone, VerificationPurpose purpose,
            String submittedCode, OffsetDateTime now) {
        VerificationCode latest = verificationCodeRepository
                .findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID));
        if (!latest.verify(submittedCode, now)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
        latest.consume(now);

        if (purpose == VerificationPurpose.LOGIN_ID) {
            return RecoverResponse.loginIdRevealed(account.getLoginId());
        }
        String temporaryPassword = generateTemporaryPassword();
        account.changePassword(passwordEncoder.encode(temporaryPassword));
        refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
        return RecoverResponse.passwordReset(temporaryPassword);
    }

    /** {@code type} 이 사양 두 값(login_id·password) 밖이면 {@code 422 VALIDATION_FAILED}(API_SPEC §2.9). */
    private VerificationPurpose parsePurpose(String rawType) {
        try {
            return VerificationPurpose.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String generateNumericCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(random.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
