package src.backend.bus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 차량 등록 요청(API_SPEC §5.12).
 *
 * <p>{@code student_capacity} 필드가 <b>부재한 것이 사양</b>이다 — 응답 전용 자동 계산값이며 관계자가
 * 입력하지 않는다(§5.12 · Ruling 155). 기사·동승자 수도 요청 필드가 아니라 ERD 기본값을 쓴다.
 *
 * <p>길이 상한은 {@code bus} 테이블의 컬럼 정의를 그대로 옮겼다.
 */
public record BusRegisterRequest(
        @NotBlank @Size(max = 20) String busNo,
        @NotBlank @Size(max = 20) String plateNo,
        @NotNull @Positive Integer capacity,
        Boolean operable) {
}
