package src.backend.routing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import src.backend.request.dto.RoutePreviewResponse;

/**
 * 강제 경유 지점 지정·제거 응답(RTE-10, API_SPEC §5.15) — {@code route_preview} 는 §5.5 상세와
 * 같은 모양을 재사용한다({@link RoutePreviewResponse}). {@code applied=false} 는 미리보기만 계산한
 * 것이라 확정 노선({@code confirmed_route}·{@code route_version})은 그대로다.
 */
public record WaypointResponse(Long waypointId, RoutePreviewResponse routePreview, OffsetDateTime estTimeBefore,
        OffsetDateTime estTimeAfter, BigDecimal estDistanceBefore, BigDecimal estDistanceAfter, boolean applied) {
}
