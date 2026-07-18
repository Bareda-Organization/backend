package src.backend.user.service.impl;

import src.backend.user.service.spec.MemberService;

import java.util.List;

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
 * {@link MemberService} 기본 구현 — 관리자에 의한 구성원 계정+멤버십 생성/목록.
 * 학원 격리는 TenantGuard 로 검사한다(학원 관리자는 자기 학원만, 플랫폼 관리자는 지정 학원).
 * 계정 생성 로직은 AuthServiceImpl.signup 과 동일하되, 관리자 인증·테넌트 격리가 앞단에 붙는다.
 */
@Service
public class MemberServiceImpl implements MemberService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberServiceImpl(UserRepository userRepository,
                             UserTenantRoleRepository userTenantRoleRepository,
                             TenantRepository tenantRepository,
                             PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public List<MemberResponse> list(AuthUser admin, Long tenantId, Role role) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<UserTenantRole> memberships = (role == null)
                ? userTenantRoleRepository.findByTenantId(effectiveTenant)
                : userTenantRoleRepository.findByTenantIdAndRole(effectiveTenant, role);
        return memberships.stream().map(MemberResponse::of).toList();
    }
}
