package src.backend.schedule.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 운행 스케줄 수정 요청(API_SPEC §5.10 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>유일성 조합 넷({@code busId}·{@code weekday}·{@code direction}·{@code departTime})도 수정
 * 대상이다 — 그중 하나만 고쳐도 기존 스케줄과 충돌하면 {@code 409 DUPLICATE_SCHEDULE} 다.
 */
public record ScheduleUpdateRequest(
        Long busId,
        String weekday,
        String direction,
        String departTime,
        @Size(max = 100) String originName,
        @Size(max = 100) String destinationName,
        @Positive Integer estDurationMin,
        Boolean active) {
}
