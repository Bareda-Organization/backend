package src.backend.bus.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 차량 수정 요청(API_SPEC §5.12 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>등록과 마찬가지로 {@code student_capacity} 를 받지 않는다. 정원({@code capacity})을 고치면
 * 학생 정원이 <b>서버에서 다시 계산</b>된다.
 */
public record BusUpdateRequest(
        @Size(max = 20) String busNo,
        @Size(max = 20) String plateNo,
        @Positive Integer capacity,
        Boolean operable) {
}
