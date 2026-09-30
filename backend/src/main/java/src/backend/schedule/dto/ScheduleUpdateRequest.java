package src.backend.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import src.backend.global.request.Patch;

/**
 * 운행 스케줄 수정 요청(API_SPEC §5.10 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다(Ruling 390).
 *
 * <p>항목이 {@link Patch} 인 것은 키가 없는 것({@code null} — 유지)과 명시적 {@code null}({@code Patch.of(null)} — 선택
 * 항목이면 지움, 필수 항목이면 {@code 422})을 가르기 위해서다. 해석 규칙은 {@link Patch}.
 * 선택(○) 항목은 {@code estDurationMin} 하나다 — {@code active} 는 ○ 이지만 지울 값이 아니라 켜고 끄는 값이라 필수처럼 다룬다.
 *
 * <p>유일성 조합 넷({@code busId}·{@code weekday}·{@code direction}·{@code departTime})도 수정
 * 대상이다 — 그중 하나만 고쳐도 기존 스케줄과 충돌하면 {@code 409 DUPLICATE_SCHEDULE} 다.
 */
public record ScheduleUpdateRequest(
        @Schema(implementation = Long.class) Patch<Long> busId,
        @Schema(implementation = String.class) Patch<String> weekday,
        @Schema(implementation = String.class) Patch<String> direction,
        @Schema(implementation = String.class) Patch<String> departTime,
        @Schema(implementation = String.class) Patch<@Size(max = 100) String> originName,
        @Schema(implementation = String.class) Patch<@Size(max = 100) String> destinationName,
        @Schema(implementation = Integer.class) Patch<@Positive Integer> estDurationMin,
        @Schema(implementation = Boolean.class) Patch<Boolean> active) {
}
