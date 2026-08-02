package src.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import src.backend.user.entity.Role;

/**
 * 구성원 수정 요청 — 전달된 필드만 갱신한다(null = 그대로).
 *
 * <p>email 은 로그인 ID라 바뀔 때만 중복 검사를 다시 한다.
 * role 은 user_tenant_role.role 을 바꾼다 — PLATFORM_ADMIN 으로는 바꿀 수 없다(권한 상승 차단).
 *
 * <p>⚠️ User–Tenant 는 N:M 이라 이름·전화·사진은 <b>그 사람이 속한 모든 학원에서 함께 바뀐다</b>
 * (app_user 가 한 벌뿐이다). 학원별 표시명은 이번 범위 밖이다.
 */
public record UpdateMemberRequest(
        @Email @Schema(example = "driver@school.com") String email,
        @Schema(example = "정기사") String name,
        @Schema(example = "010-2345-6789") String phone,
        @Schema(example = "https://cdn.example.com/photo/driver1.png") String photoUrl,
        @Schema(example = "DRIVER") Role role) {
}
