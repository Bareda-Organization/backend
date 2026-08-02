package src.backend.auth.command;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.auth.dto.SignupRequest;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 회원가입(self-service) — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다.
 * 요청 body 의 role 을 그대로 믿으면 누구나 임의 학원의 관리자로 가입할 수 있으므로,
 * 관리자가 부여하는 역할(ATTENDANT·ACADEMY_ADMIN·PLATFORM_ADMIN)은 여기서 거부한다.
 */
@Service
public class AuthCommandService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthCommandService(UserRepository userRepository,
                              UserTenantRoleRepository userTenantRoleRepository,
                              TenantRepository tenantRepository,
                              PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 관리자가 부여하는 역할 — self-service 회원가입으로는 만들 수 없다. */
    private static final Set<Role> ADMIN_GRANTED_ROLES =
            EnumSet.of(Role.ATTENDANT, Role.ACADEMY_ADMIN, Role.PLATFORM_ADMIN);

    @Transactional
    public void signup(SignupRequest req) {
        if (ADMIN_GRANTED_ROLES.contains(req.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자가 부여하는 역할은 회원가입으로 만들 수 없습니다");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (req.tenantId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "학원(tenantId)이 필요합니다");
        }
        Tenant tenant = tenantRepository.findById(req.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));

        User user = userRepository.save(User.builder()
                .email(req.email()).password(passwordEncoder.encode(req.password()))
                .name(req.name()).phone(req.phone()).build());

        userTenantRoleRepository.save(UserTenantRole.builder()
                .user(user).tenant(tenant).role(req.role()).build());
    }
}
