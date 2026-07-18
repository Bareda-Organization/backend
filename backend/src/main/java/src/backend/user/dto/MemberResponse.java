package src.backend.user.dto;

import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;

/**
 * 구성원 응답 — 계정 + 학원·역할 멤버십.
 */
public record MemberResponse(
        Long userId,
        String email,
        String name,
        Role role,
        Long tenantId) {

    public static MemberResponse of(UserTenantRole membership) {
        return new MemberResponse(
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getName(),
                membership.getRole(),
                membership.getTenant() != null ? membership.getTenant().getId() : null);
    }
}
