package src.backend.request.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 재최적화 전/후 미리보기의 정차지 한 자리(API_SPEC §5.5 상세 — {@code stops_before}·{@code stops_after}).
 *
 * @param lat @param lng 관계자 웹 지도에 마커로 찍는 데 쓴다(R21-A 추가 지시 ②, 사용자 지적 — "구간별
 *         승인의 지도에도 승하차지 표기"). 경유 지점(waypoint)만 가리키는 항목은 좌표를 안 실어
 *         {@code null} 이다 — 화면은 그 마커만 건너뛰고 나머지를 그린다(좌표 없이 억지로 맞추면
 *         삭제된 승하차지가 조용히 빠진 지도가 나간다).
 */
public record PreviewStopResponse(int seq, String stopName, OffsetDateTime eta, BigDecimal lat, BigDecimal lng) {
}
