package src.backend.routing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import src.backend.request.dto.RoutePreviewResponse;

/**
 * 강제 경유 지점 지정·제거 응답(RTE-10, API_SPEC §5.15) — {@code route_preview} 는 §5.5 상세와
 * 같은 모양을 재사용한다({@link RoutePreviewResponse}, {@code road_path_before}·{@code road_path_after}
 * 포함, `R18-C2`). {@code applied=false} 는 미리보기만 계산한 것이라 확정 노선({@code confirmed_route}·
 * {@code route_version})은 그대로다.
 *
 * @param estDurationBefore 노선 전체 소요(분, `Ruling 318` 과 같은 근거) — §5.5 상세와 같이
 *                          {@code route_version.est_duration_min} 을 새로 계산하지 않고 그대로 싣는다
 * @param estDurationAfter 이 경유 지점을 반영한 재최적화 결과의 노선 전체 소요(분)
 * @param previewToken 미리보기일 때만 — 배포 요청에 그대로 돌려보낸다(BR-051). 배포 응답에서는 {@code null}
 */
public record WaypointResponse(Long waypointId, RoutePreviewResponse routePreview, OffsetDateTime estTimeBefore,
        OffsetDateTime estTimeAfter, BigDecimal estDistanceBefore, BigDecimal estDistanceAfter,
        Integer estDurationBefore, Integer estDurationAfter, boolean applied, String previewToken) {
}
