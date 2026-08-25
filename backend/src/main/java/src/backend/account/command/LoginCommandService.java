package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.dto.LoginFailureDetail;
import src.backend.account.entity.Account;
import src.backend.account.entity.RefreshToken;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtTokenProvider;

/**
 * 로그인(AUTH-04·05, API_SPEC §2.5) — 자격 대조 → 실패 누적/차단 판정 → 토큰 발급까지 담당한다.
 * 클라이언트 종류(app/web)는 전혀 모른다 — 발급한 원문 토큰을 어떻게 응답에 실을지는
 * {@code AuthController} 가 판단한다(브리프 §3).
 */
@Service
public class LoginCommandService {

    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;
    private final long refreshValiditySeconds;

    public LoginCommandService(AccountRepository accountRepository, AcademyRepository academyRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider, Clock clock,
            @Value("${jwt.refresh-token-validity-seconds}") long refreshValiditySeconds) {
        this.accountRepository = accountRepository;
        this.academyRepository = academyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clock = clock;
        this.refreshValiditySeconds = refreshValiditySeconds;
    }

    /**
     * {@code pending}·{@code rejected} 도 로그인은 성공한다(API_SPEC §2.5) — 접근 범위 축소는
     * 계정 상태 게이트(§1.4)가 별도로 담당한다. 이 메서드는 자격 증명과 차단 여부만 본다.
     *
     * <p>미등록 {@code login_id} 도 존재하는 계정의 첫 실패와 <b>본문 형태가 같아야 한다</b> —
     * {@code details.remaining_attempts} 를 한쪽에만 실으면 상태 코드가 같아도 그 유무로 계정 존재
     * 여부를 가려낼 수 있다(계정 열거, 리뷰 라운드 1 I5). 미등록에는 누적할 카운터가 없으므로
     * "아직 한 번도 실패하지 않은 계정" 과 같은 값인 {@link Account#MAX_FAILED_ATTEMPTS} 를 싣는다.
     *
     * <p>실패 시 {@link Account#recordLoginFailure} 가 상한 도달을 판정해 {@code blocked} 로
     * 전이시키면, 그 즉시 계정의 유효 refresh 토큰을 전량 무효화한다 — API_SPEC §1.2 는 "차단 시
     * 무효화"만 적고 트리거를 명시하지 않는데, 이 로그인 경로에서의 차단도 그 트리거에 포함시킨
     * 판단이다(Task 4 판단, 보고서 ⑥).
     */
    @Transactional
    public LoginResult login(String loginId, String rawPassword) {
        Account account = accountRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                        new LoginFailureDetail(Account.MAX_FAILED_ATTEMPTS)));
        account.assertNotBlocked();

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            int remaining = account.recordLoginFailure(now);
            if (account.getStatus() == AccountStatus.BLOCKED) {
                refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
                throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, new LoginFailureDetail(remaining));
        }
        account.recordLoginSuccess(now);

        String accessToken = jwtTokenProvider.createAccessToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        String refreshToken = jwtTokenProvider.createRefreshToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        refreshTokenRepository.save(RefreshToken.issue(account.getId(),
                RefreshTokenHasher.sha256Hex(refreshToken), now, now.plusSeconds(refreshValiditySeconds), null));

        String academyName = resolveAcademyName(account);
        return new LoginResult(accessToken, refreshToken, refreshValiditySeconds, account.getId(),
                account.getAcademyId(), account.getRole(), account.getStatus(), academyName);
    }

    /** {@code system_admin} 은 소속 학원이 없다(API_SPEC §2.5 {@code academy} = null). */
    private String resolveAcademyName(Account account) {
        if (account.getAcademyId() == null) {
            return null;
        }
        return academyRepository.findById(account.getAcademyId()).map(Academy::getName).orElse(null);
    }
}
