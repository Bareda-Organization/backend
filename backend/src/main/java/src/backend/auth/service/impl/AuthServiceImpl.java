package src.backend.auth.service.impl;

import src.backend.auth.service.spec.AuthService;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import src.backend.auth.dto.LoginRequest;
import src.backend.auth.dto.SignupRequest;
import src.backend.auth.dto.TokenResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.JwtTokenProvider;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * {@link AuthService} 기본 구현 — 회원가입/로그인/토큰 재발급.
 * 비밀번호는 BCrypt 로 단방향 저장하고, 로그인 시 사용자의 학원·역할(멤버십)을 토큰에 담아 발급한다.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthServiceImpl(UserRepository userRepository,
                           UserTenantRoleRepository userTenantRoleRepository,
                           TenantRepository tenantRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Override
    @Transactional
    public void signup(SignupRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = userRepository.save(User.builder()
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(req.phone())
                .build());

        if (req.role() == Role.PLATFORM_ADMIN) {
            // 플랫폼 관리자: 특정 학원에 속하지 않는 전역 역할(tenant = null)
            userTenantRoleRepository.save(UserTenantRole.builder()
                    .user(user).tenant(null).role(Role.PLATFORM_ADMIN).build());
        } else {
            if (req.tenantId() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "학원(tenantId)이 필요합니다");
            }
            Tenant tenant = tenantRepository.findById(req.tenantId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
            userTenantRoleRepository.save(UserTenantRole.builder()
                    .user(user).tenant(tenant).role(req.role()).build());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issue(user);
    }

    @Override
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
