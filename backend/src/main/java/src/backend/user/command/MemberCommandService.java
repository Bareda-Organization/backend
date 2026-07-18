package src.backend.user.command;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.dto.CreateMemberRequest;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 구성원(계정+학원 멤버십) 등록 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다.
 * self-service 가입(AuthCommandService.signup)과 달리, 관리자가 자기 학원 구성원을 프로비저닝하는 경로다.
 */
@Service
public class MemberCommandService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberCommandService(UserRepository userRepository,
                                UserTenantRoleRepository userTenantRoleRepository,
                                TenantRepository tenantRepository,
                                PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public MemberResponse register(AuthUser admin, CreateMemberRequest req) {
        if (req.role() == Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "플랫폼 관리자는 이 경로로 등록할 수 없습니다");
        }
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, req.tenantId());
        Tenant tenant = tenantRepository.findById(effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        User user = userRepository.save(User.builder()
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(req.phone())
                .build());
        UserTenantRole membership = userTenantRoleRepository.save(UserTenantRole.builder()
                .user(user).tenant(tenant).role(req.role()).build());
        return MemberResponse.of(membership);
    }
}
