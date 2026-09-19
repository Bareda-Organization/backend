package src.backend.request.dto;

import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 재최적화 결과 미리보기(API_SPEC §5.5 상세) — 승인 전 노선의 전/후 대조.
 *
 * <p>{@code roadPathBefore}·{@code roadPathAfter}(`R18-C` 목표 4, {@code Ruling 319})는 좌우 두 지도로
 * 전/후 경로를 나란히 그릴 좌표열이다 — {@code stops_before}·{@code stops_after} 와 같은 전/후 짝
 * 구조를 따른다. 경유 지점 미리보기(§5.15, {@link src.backend.routing.command.WaypointCommandService})
 * 도 `R18-C2` 부터 같은 두 필드를 채운다 — 전/후 도로 좌표 없이 조립하는 경로는 더 이상 없다.
 */
public record RoutePreviewResponse(
        List<PreviewStopResponse> stopsBefore,
        List<PreviewStopResponse> stopsAfter,
        List<StopRefResponse> reordered,
        List<StopRefResponse> removed,
        List<GeoPoint> roadPathBefore,
        List<GeoPoint> roadPathAfter) {

    public static RoutePreviewResponse of(List<PreviewStopResponse> stopsBefore,
            List<PreviewStopResponse> stopsAfter, List<StopRefResponse> reordered, List<StopRefResponse> removed,
            List<GeoPoint> roadPathBefore, List<GeoPoint> roadPathAfter) {
        return new RoutePreviewResponse(List.copyOf(stopsBefore), List.copyOf(stopsAfter), List.copyOf(reordered),
                List.copyOf(removed), List.copyOf(roadPathBefore), List.copyOf(roadPathAfter));
    }
}
