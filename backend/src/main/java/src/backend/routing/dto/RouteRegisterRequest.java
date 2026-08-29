package src.backend.routing.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 고정 노선 편성 요청(API_SPEC §5.9 · Ruling 180).
 *
 * <p>{@code weekday}·{@code direction} 을 {@code String} 으로 받는다 — 값 도메인 타입으로 받으면
 * 어긋난 값이 Jackson 역직렬화 단계에서 깨져, {@code 422} 여야 할 입력이 {@code 400} 이 된다
 * ({@code ScheduleRegisterRequest} 와 같은 이유). 변환과 거부는 {@code ApiValues} 한 곳이 맡는다.
 *
 * <p>학원을 담을 자리가 <b>부재한 것이 사양</b>이다 — 소속은 토큰이 정한다(§1.5).
 *
 * @param stopIds 정차 순서 — <b>보낸 차례가 그대로 {@code seq} 1..N 이다.</b> 주지 않으면 정차지 없는
 *                편성으로 시작한다(차량·요일·방향 칸을 먼저 잡아 두고 승하차지를 나중에 채우는 조작이
 *                실재하므로 필수로 두지 않는다)
 */
public record RouteRegisterRequest(
        @NotNull Long busId,
        @NotBlank String weekday,
        @NotBlank String direction,
        @Size(max = 100) String name,
        Boolean active,
        List<Long> stopIds) {
}
