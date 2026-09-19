package src.backend.request.dto;

import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 재최적화 결과 미리보기(API_SPEC §5.5 상세) — 승인 전 노선의 전/후 대조.
 *
 * <p>{@code roadPathBefore}·{@code roadPathAfter}(`R18-C` 목표 4, {@code Ruling 319})는 좌우 두 지도로
 * 전/후 경로를 나란히 그릴 좌표열이다 — {@code stops_before}·{@code stops_after} 와 같은 전/후 짝
 * 구조를 따른다. 경유 지점 미리보기(§5.15, {@link src.backend.routing.command.WaypointCommandService})
 * 는 이 레코드를 그대로 재사용하되 아직 이 두 필드를 채우지 않는다 — 빈 배열로 남는다.
 */
public record RoutePreviewResponse(
        List<PreviewStopResponse> stopsBefore,
        List<PreviewStopResponse> stopsAfter,
        List<StopRefResponse> reordered,
        List<StopRefResponse> removed,
        List<GeoPoint> roadPathBefore,
        List<GeoPoint> roadPathAfter) {

    /** 도로 좌표 없이 정차 대조만 채운다(§5.15 경유 지점 미리보기 — 아직 지도 좌표를 안 낸다). */
    public static RoutePreviewResponse of(List<PreviewStopResponse> stopsBefore,
            List<PreviewStopResponse> stopsAfter, List<StopRefResponse> reordered, List<StopRefResponse> removed) {
        return of(stopsBefore, stopsAfter, reordered, removed, List.of(), List.of());
    }

    /** §5.5 승인 상세 — 전/후 도로 좌표까지 함께 채운다(`R18-C` 목표 4, {@code Ruling 319}). */
    public static RoutePreviewResponse of(List<PreviewStopResponse> stopsBefore,
            List<PreviewStopResponse> stopsAfter, List<StopRefResponse> reordered, List<StopRefResponse> removed,
            List<GeoPoint> roadPathBefore, List<GeoPoint> roadPathAfter) {
        return new RoutePreviewResponse(List.copyOf(stopsBefore), List.copyOf(stopsAfter), List.copyOf(reordered),
                List.copyOf(removed), List.copyOf(roadPathBefore), List.copyOf(roadPathAfter));
    }
}
