package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.RefreshToken;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 로그아웃(AUTH-09, API_SPEC §2.7) — {@code pending} 을 포함한 전 역할이 호출할 수 있어
 * {@code AuthController} 에서 {@code @AllowedWhenPending} 이 붙는다. 이 서비스는 해당 refresh
 * 토큰 1건만 무효화하고, 같은 계정의 다른 단말 세션은 건드리지 않는다.
 */
@Service
public class LogoutCommandService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public LogoutCommandService(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    /**
     * 인증 필터가 이미 액세스 토큰으로 신원을 확인했으므로, 이 refresh 토큰이 그 신원의 소유인지
     * 대조한다(브리프에 없는 소유권 검증 — Task 4 판단, 보고서 ⑥) — 검증 없이 임의의 refresh
     * 토큰 문자열을 보내 남의 세션을 로그아웃시키는 경로를 막는다. 이미 무효화됐거나, 존재하지
     * 않거나, 소유자가 다르면 전부 {@code 401 TOKEN_EXPIRED} 하나로 응답한다(API_SPEC §2.7) —
     * "존재하지 않음"과 "소유자가 다름"을 구분해 알려주면 토큰 추측에 단서를 준다.
     */
    @Transactional
    public void logout(Long authenticatedAccountId, String rawRefreshToken) {
        String tokenHash = RefreshTokenHasher.sha256Hex(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(t -> t.getRevokedAt() == null)
                .filter(t -> t.getAccountId().equals(authenticatedAccountId))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_EXPIRED));

        OffsetDateTime now = OffsetDateTime.now(clock);
        refreshTokenRepository.revokeByTokenHash(stored.getTokenHash(), now);
    }
}
