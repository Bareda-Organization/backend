package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.entity.RefreshToken;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtTokenProvider;

/**
 * 토큰 재발급(C-14, API_SPEC §2.6) — refresh 원문 문자열 하나만 받는다. 그 문자열이 쿠키에서
 * 왔는지 본문에서 왔는지는 전혀 모른다(브리프 §3) — 그 판정과, 응답을 쿠키로 내릴지 본문으로
 * 내릴지의 판정은 전부 {@code AuthController} 몫이다.
 */
@Service
@RequiredArgsConstructor
public class RefreshCommandService {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;
    @Value("${jwt.refresh-token-validity-seconds}")
    private final long refreshValiditySeconds;

    /**
     * refresh 토큰을 회전한다 — 서명·만료(JWT 자체) 검증에 실패하거나, DB 상 이미 무효화된
     * 토큰이면 전부 {@code 401 TOKEN_EXPIRED} 하나로 응답한다(API_SPEC §2.6) — 원인을 세분화해
     * 알려주면 공격자가 "이 토큰은 서명이 깨졌다"와 "이 토큰은 로그아웃으로 무효화됐다"를 구별할
     * 단서를 얻는다.
     *
     * <p>{@code Account.assertNotBlocked()} 는 의도적으로 호출하지 않는다 — 계정이 차단되면
     * 로그인 시점에 이미 그 계정의 refresh 토큰 전량을 무효화하므로(§ {@link LoginCommandService}),
     * 차단된 계정의 토큰은 이 메서드의 "이미 무효화됨" 분기에서 자연히 걸러진다. 여기서 별도로
     * {@code AUTH_ACCOUNT_BLOCKED}(403)를 던지면 API_SPEC §2.6 에 없는 에러 코드를 이 경로에
     * 새로 만드는 셈이라 하지 않았다(Task 4 판단, 보고서 ⑥).
     *
     * <p>{@code Account} 를 매번 다시 조회하는 이유는 JWT 클레임을 신뢰하지 않기 위해서다 —
     * 로그인 이후 role·status 가 바뀌었다면(예: 승인) 새로 발급하는 access 토큰에 최신 값을
     * 실어야, 클라이언트가 재로그인 없이도 게이트 제한에서 벗어난다.
     */
    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        Claims claims = parseRefreshClaims(rawRefreshToken);
        Long accountId = Long.valueOf(claims.getSubject());

        String tokenHash = RefreshTokenHasher.sha256Hex(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(t -> t.getRevokedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_EXPIRED));

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_EXPIRED));

        OffsetDateTime now = OffsetDateTime.now(clock);
        refreshTokenRepository.revokeByTokenHash(stored.getTokenHash(), now);

        String newAccessToken = jwtTokenProvider.createAccessToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        refreshTokenRepository.save(RefreshToken.issue(account.getId(),
                RefreshTokenHasher.sha256Hex(newRefreshToken), now, now.plusSeconds(refreshValiditySeconds), null));

        return new RefreshResult(newAccessToken, newRefreshToken, refreshValiditySeconds);
    }

    private Claims parseRefreshClaims(String rawRefreshToken) {
        try {
            Claims claims = jwtTokenProvider.parse(rawRefreshToken);
            if (!jwtTokenProvider.isRefreshToken(claims)) {
                throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
            }
            return claims;
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
    }
}
