package src.backend.bus.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 차량 수정 요청(API_SPEC §5.12 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>등록과 마찬가지로 {@code student_capacity} 를 받지 않는다. 정원({@code capacity})을 고치면
 * 학생 정원이 <b>서버에서 다시 계산</b>된다.
 *
 * <p>{@code bus_no}·{@code plate_no} 는 필수(●) 항목이라 <b>보내면 공백 아닌 글자가 있어야</b> 한다(BR-324) — 빈 문자열·공백뿐은
 * {@code 422} 이고, 안 보내거나 {@code null} 이면 "유지" 다(§1.14). 등록의 {@code @NotBlank} 와 같은 규칙을 수정에도 건다.
 */
public record BusUpdateRequest(
        @Size(max = 20) @Pattern(regexp = ".*\\S.*") String busNo,
        @Size(max = 20) @Pattern(regexp = ".*\\S.*") String plateNo,
        @Positive Integer capacity,
        Boolean operable) {
}
