package src.backend.account.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;
import src.backend.account.repository.VerificationCodeRepository;

/**
 * 전화번호 복구 인증번호의 생성과 대조 — 트랜잭션은 걸지 않으며 {@link AccountRecoveryCommandService} 가 연 트랜잭션 안에서 불린다.
 */
@Component
@RequiredArgsConstructor
class RecoveryCodeVerifier {

    private static final int CODE_DIGITS = 6;

    private final VerificationCodeRepository verificationCodeRepository;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    /** 무작위 6자리 숫자 인증번호를 만든다. */
    String newCode() {
        StringBuilder code = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < CODE_DIGITS; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    /** 가장 최근에 발급된 코드와 입력이 같고 아직 쓸 수 있는지 잠금 없이 본다 — 횟수 기록·소비는 하지 않는다(그건 대조 트랜잭션이 한다). */
    boolean matchesIssuedCode(String phone, VerificationPurpose purpose, String input) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return verificationCodeRepository.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .filter(issued -> issued.getConsumedAt() == null && issued.getExpiresAt().isAfter(now))
                .filter(issued -> MessageDigest.isEqual(issued.getCode().getBytes(StandardCharsets.UTF_8),
                        input.getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }

    /** 대조 횟수를 올리고 맞으면 코드를 소비한다 — 통과하면 {@code true}, 실패는 이유와 무관하게 {@code false} 다. */
    boolean consumeIfMatches(String phone, VerificationPurpose purpose, String input, OffsetDateTime now) {
        Optional<VerificationCode> latest = verificationCodeRepository.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone,
                purpose);
        if (latest.isEmpty()) {
            return false;
        }
        VerificationCode issued = latest.get();
        // 상한 도달·소비·불일치·만료를 같은 값으로 돌려준다 — 가르면 "이 번호로 코드를 발급받은 적이 있다" 를 알려 준다.
        return verificationCodeRepository.recordAttempt(issued.getId(), VerificationCode.MAX_ATTEMPTS) != 0
                && MessageDigest.isEqual(issued.getCode().getBytes(StandardCharsets.UTF_8),
                        input.getBytes(StandardCharsets.UTF_8))
                && verificationCodeRepository.consumeIfValid(issued.getId(), now) != 0;
    }
}
