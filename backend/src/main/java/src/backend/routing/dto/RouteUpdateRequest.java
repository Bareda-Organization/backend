package src.backend.routing.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * 고정 노선 수정 요청(API_SPEC §5.9 {@code PATCH}) — 보내지 않은 필드는 고치지 않는다.
 *
 * <p>유일성 조합 셋({@code busId}·{@code weekday}·{@code direction})도 수정 대상이다 — 그중 하나만
 * 고쳐도 기존 편성과 충돌하면 {@code 409 DUPLICATE_ROUTE} 다.
 *
 * @param stopIds 보내면 정차 순서를 <b>통째로</b> 그 차례로 갈아 끼우고, 보내지 않으면 손대지 않는다.
 *                항목별 삽입·삭제를 두지 않은 이유는 순번이 {@code uk_route_stop_route_seq} 로
 *                묶여 있어 한 자리만 고치는 조작이 나머지 순번을 전부 다시 매기게 하기 때문이다 —
 *                그럴 바에는 전체 교체 하나만 두는 편이 실패 지점이 적다
 */
public record RouteUpdateRequest(
        Long busId,
        String weekday,
        String direction,
        @Size(max = 100) String name,
        Boolean active,
        List<Long> stopIds) {
}
