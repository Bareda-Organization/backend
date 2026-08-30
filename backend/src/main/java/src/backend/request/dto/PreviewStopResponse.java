package src.backend.request.dto;

import java.time.OffsetDateTime;

/** 재최적화 전/후 미리보기의 정차지 한 자리(API_SPEC §5.5 상세 — {@code stops_before}·{@code stops_after}). */
public record PreviewStopResponse(int seq, String stopName, OffsetDateTime eta) {
}
