package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 비밀번호 변경(AUTH-07, API_SPEC §2.8) — 현재 비밀번호를 대조한 뒤 바꾸고, 성공하면 이 계정의
 * refresh 토큰을 전량 무효화한다(재로그인 강제).
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeCommandService {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** 현재 비밀번호 불일치 시 {@code 401 INVALID_CREDENTIALS}(API_SPEC §2.8) — 형식 오류는 {@code @Valid} 가 앞단에서 걸러 {@code 422} 로 응답한다. */
    @Transactional
    public void changePassword(Long accountId, String currentPassword, String newPassword) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        account.changePassword(passwordEncoder.encode(newPassword));

        OffsetDateTime now = OffsetDateTime.now(clock);
        refreshTokenRepository.revokeAllValidByAccountId(accountId, now);
    }
}
