package src.backend.request.dto;

import java.util.List;

/** 재최적화 결과 미리보기(API_SPEC §5.5 상세) — 승인 전 노선의 전/후 대조. */
public record RoutePreviewResponse(
        List<PreviewStopResponse> stopsBefore,
        List<PreviewStopResponse> stopsAfter,
        List<StopRefResponse> reordered,
        List<StopRefResponse> removed) {

    public static RoutePreviewResponse of(List<PreviewStopResponse> stopsBefore,
            List<PreviewStopResponse> stopsAfter, List<StopRefResponse> reordered, List<StopRefResponse> removed) {
        return new RoutePreviewResponse(List.copyOf(stopsBefore), List.copyOf(stopsAfter),
                List.copyOf(reordered), List.copyOf(removed));
    }
}
