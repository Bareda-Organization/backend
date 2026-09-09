package src.backend.manager.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Size;

/**
 * 매니저 수정 요청(API_SPEC §5.13 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>{@code role} 변경은 곧 앱 권한 변경이다(C-06 · §5.13) — 화면 구성이 함께 바뀐다.
 */
public record ManagerUpdateRequest(
        @Size(max = 50) String name,
        @Size(max = 30) String phone,
        String role,
        Map<String, List<Map<String, String>>> workHours) {
}
