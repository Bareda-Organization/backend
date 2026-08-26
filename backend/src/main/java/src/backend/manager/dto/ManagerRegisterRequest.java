package src.backend.manager.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 매니저 등록 요청(API_SPEC §5.13).
 *
 * <p>{@code role} 을 {@code String} 으로 받는다 — enum 으로 받으면 값이 어긋난 요청이 Jackson 역직렬화
 * 단계에서 깨져 {@code 422} 여야 할 입력이 {@code 500} 이 된다.
 *
 * <p>{@code workHours} 도 같은 이유로 원문 {@code Map} 이다. 검증과 형태 고정은
 * {@code WorkHours}(Ruling 150)가 한 곳에서 맡는다 — 요청 DTO 에 형태를 박으면 DTO 를 거치지 않는
 * 입력 경로가 생길 때 검증이 함께 사라진다.
 */
public record ManagerRegisterRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 30) String phone,
        @NotBlank String role,
        Map<String, List<Map<String, String>>> workHours) {
}
