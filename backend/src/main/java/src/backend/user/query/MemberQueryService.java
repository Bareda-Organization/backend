package src.backend.user.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/** 구성원(학원 멤버십) 목록 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class MemberQueryService {

    private final UserTenantRoleRepository userTenantRoleRepository;

    public MemberQueryService(UserTenantRoleRepository userTenantRoleRepository) {
        this.userTenantRoleRepository = userTenantRoleRepository;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(AuthUser admin, Long tenantId, Role role) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<UserTenantRole> memberships = (role == null)
                ? userTenantRoleRepository.findByTenantId(effectiveTenant)
                : userTenantRoleRepository.findByTenantIdAndRole(effectiveTenant, role);
        return memberships.stream().map(MemberResponse::of).toList();
    }
}
