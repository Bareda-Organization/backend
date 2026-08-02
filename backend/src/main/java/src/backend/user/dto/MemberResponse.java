package src.backend.user.dto;

import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;

/**
 * 구성원 응답 — 계정 + 학원·역할 멤버십.
 *
 * <p>목록 화면이 인물 카드(사진·이름·전화)를 그리므로 phone·photoUrl 을 여기서 함께 준다 —
 * 없으면 줄마다 상세를 다시 조회해야 한다.
 */
public record MemberResponse(
        Long userId,
        String email,
        String name,
        String phone,
        String photoUrl,
        Role role,
        Long tenantId) {

    public static MemberResponse of(UserTenantRole membership) {
        return new MemberResponse(
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getName(),
                membership.getUser().getPhone(),
                membership.getUser().getPhotoUrl(),
                membership.getRole(),
                membership.getTenant() != null ? membership.getTenant().getId() : null);
    }
}
