package src.backend.auth.query;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import src.backend.auth.dto.LoginRequest;
import src.backend.auth.dto.TokenResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.JwtTokenProvider;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 로그인/토큰 재발급 — 자격 검증 후 토큰을 발급한다. DB 쓰기가 없는 순수 조회+토큰 발급이라 Query로 분류.
 * 단순 CRUD류라 인터페이스 없이 concrete 클래스로 둔다.
 */
@Service
public class AuthQueryService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthQueryService(UserRepository userRepository,
                            UserTenantRoleRepository userTenantRoleRepository,
                            PasswordEncoder passwordEncoder,
                            JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = tokenProvider.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 토큰입니다");
        }
        if (!tokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refresh 토큰이 아닙니다");
        }
        User user = userRepository.findById(Long.valueOf(claims.getSubject()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return issue(user);
    }

    private TokenResponse issue(User user) {
        List<String> memberships = encodeMemberships(user.getId());
        String access = tokenProvider.createAccessToken(user.getId(), user.getEmail(), memberships);
        String refresh = tokenProvider.createRefreshToken(user.getId(), user.getEmail(), memberships);
        return new TokenResponse(access, refresh);
    }

    /** 사용자의 (학원, 역할) 멤버십을 토큰 클레임용 "tenantId:ROLE" 문자열 목록으로 변환. */
    private List<String> encodeMemberships(Long userId) {
        return userTenantRoleRepository.findByUserId(userId).stream()
                .map(utr -> AuthUser.encode(new AuthUser.Membership(
                        utr.getTenant() == null ? null : utr.getTenant().getId(), utr.getRole())))
                .toList();
    }
}
