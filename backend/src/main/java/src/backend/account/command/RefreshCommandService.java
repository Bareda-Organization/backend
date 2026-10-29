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
import src.backend.global.security.AccountStanding;
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
    private final AccountStanding accountStanding;
    private final Clock clock;
    @Value("${jwt.refresh-token-validity-seconds}")
    private final long refreshValiditySeconds;

    /**
     * refresh 토큰을 회전한다 — 서명·만료(JWT 자체) 검증에 실패하거나, DB 상 이미 무효화된
     * 토큰이면 전부 {@code 401 TOKEN_EXPIRED} 하나로 응답한다(API_SPEC §2.6) — 원인을 세분화해
     * 알려주면 공격자가 "이 토큰은 서명이 깨졌다"와 "이 토큰은 로그아웃으로 무효화됐다"를 구별할
     * 단서를 얻는다.
     *
     * <p>권한을 잃은 계정(차단 · 퇴사 · 삭제된 매니저)은 {@code accountStanding.isLost} 가 막는다(Ruling 880) — {@code
     * Account.assertNotBlocked()} 의 403({@code AUTH_ACCOUNT_BLOCKED})을 던지면 API_SPEC §2.6 에 없는 에러 코드를 이 경로에 새로 만드는
     * 셈이라 응답은 {@code TOKEN_EXPIRED} 하나다.
     *
     * <p>접근을 끊는 경로는 그 계정의 refresh 토큰도 함께 전량 무효화하므로 대개 아래 "이미 무효화됨" 분기에서 먼저 걸린다 — 로그인 실패
     * 누적 차단({@code LoginCommandService}) · 비밀번호 변경·복구(§2.8·§2.9) · 관계자 퇴사와 비밀번호 초기화({@code
     * StaffAccountCommandService}, §6.7) · 매니저 삭제({@code ManagerDeletedSessionListener}). 다섯째 경로를 만드는 쪽이 {@code
     * RefreshTokenRepository#revokeAllValidByAccountId} 를 빠뜨려도 {@code isLost} 확인이 재발급을 막지만, 상태 보관 항목의 지연만큼은
     * 열려 있다.
     *
     * <p>{@code Account} 를 매번 다시 조회하는 이유는 JWT 클레임을 신뢰하지 않기 위해서다 —
     * 로그인 이후 role·status 가 바뀌었다면(예: 승인) 새로 발급하는 access 토큰에 최신 값을
     * 실어야, 클라이언트가 재로그인 없이도 게이트 제한에서 벗어난다.
     */
    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        Claims claims = parseRefreshClaims(rawRefreshToken);
        Long accountId = Long.valueOf(claims.getSubject());

        RefreshToken stored = findValidStoredToken(rawRefreshToken);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_EXPIRED));

        assertStandingKept(accountId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        revokeStored(stored, now);
        return issueRotated(account, now);
    }

    /** 원문 해시로 저장된 토큰을 찾는다 — 없거나 이미 무효화됐으면 {@code TOKEN_EXPIRED}. */
    private RefreshToken findValidStoredToken(String rawRefreshToken) {
        String tokenHash = RefreshTokenHasher.sha256Hex(rawRefreshToken);
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(t -> t.getRevokedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_EXPIRED));
    }

    private void assertStandingKept(Long accountId) {
        // 권한을 잃은 계정(차단 · 퇴사 · 삭제된 매니저)은 재발급하지 않는다(Ruling 880) — access 가 401 TOKEN_EXPIRED 로 막히는데 재발급이
        // 200 이면 클라이언트가 로그인으로 가지 못하고 재발급 → 401 을 되풀이한다. 위 무효화 분기와 겹치는 확인이지만, 무효화가 아직
        // 닿지 않은 틈(경합 · 보관 항목)에도 재발급이 나가지 않게 둔다
        if (accountStanding.isLost(accountId)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
    }

    private void revokeStored(RefreshToken stored, OffsetDateTime now) {
        // 조건부 무효화의 결과로 선점을 판정한다(BR-061) — 동시 요청이 둘 다 "미해지" 를 읽어도 무효화에 성공한
        // 한 건만 새 토큰을 받는다. 결과를 버리면 세션 사슬이 두 갈래로 갈린다.
        if (refreshTokenRepository.revokeByTokenHash(stored.getTokenHash(), now) != 1) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
    }

    /** 최신 계정 상태로 access · refresh 토큰을 새로 만들고 refresh 해시를 저장한다. */
    private RefreshResult issueRotated(Account account, OffsetDateTime now) {
        String newAccessToken = jwtTokenProvider.createAccessToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus(), account.isMustChangePassword());
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
