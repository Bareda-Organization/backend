package src.backend.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 특정일 회차 임시 추가 요청(SCH-03, API_SPEC §5.10 {@code POST /staff/runs}).
 *
 * <p>{@code scheduleId} 를 받는 자리가 <b>부재한 것이 사양</b>이다 — 임시 회차는 정규 스케줄에서
 * 나오지 않은 1회성 운행이고, 그 사실을 {@code run.schedule_id} 가 비어 있는 것으로 표시한다.
 *
 * <p>날짜·시각·방향을 {@code String} 으로 받는 이유는 {@code ScheduleRegisterRequest} 와 같다 —
 * 어긋난 값이 역직렬화 단계에서 깨지면 {@code 422} 여야 할 입력이 {@code 400} 이 된다.
 */
public record RunCreateRequest(
        @NotNull Long busId,
        @NotBlank String serviceDate,
        @NotBlank String direction,
        @NotBlank String departTime,
        @NotBlank @Size(max = 100) String originName,
        @NotBlank @Size(max = 100) String destinationName,
        @Positive Integer estDurationMin) {
}
