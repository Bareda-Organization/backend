package src.backend.auth.command;

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

/** 회원가입(self-service) — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
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
}
