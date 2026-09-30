package src.backend.manager.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 매니저 수정 요청(API_SPEC §5.13 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>{@code name}·{@code phone} 은 <b>안 보내는 것만</b> "그대로 둠" 이다 — 빈 문자열·공백만은 거부한다(BR-245,
 * 등록의 {@code @NotBlank} 와 같은 기준). {@code @Pattern} 은 {@code null} 을 통과시키므로 안 보낸 수정은 영향이 없다.
 *
 * <p>{@code role} 변경은 곧 앱 권한 변경이다(C-06 · §5.13) — 화면 구성이 함께 바뀐다.
 */
public record ManagerUpdateRequest(
        @Size(max = 50) @Pattern(regexp = NOT_BLANK) String name,
        @Size(max = 30) @Pattern(regexp = NOT_BLANK) String phone,
        String role,
        Map<String, List<Map<String, String>>> workHours) {

    /** 공백이 아닌 글자가 하나라도 있는 문자열 — 줄바꿈이 섞여도 성립하도록 {@code (?s)} 다. */
    private static final String NOT_BLANK = "(?s).*\\S.*";
}
