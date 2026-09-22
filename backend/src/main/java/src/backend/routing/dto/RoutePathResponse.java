package src.backend.routing.dto;

import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 고정 노선의 <b>도로 경로</b>(API_SPEC §5.9 {@code GET /staff/routes/{id}/path} 신설, RTE-01) —
 * 정차 순서({@code route_stop.seq}) 대로 이은 실제 도로 좌표열이다. 기존 상세 응답의
 * {@code stops[]} 는 순번만 담을 뿐 좌표를 잇는 선이 없어, 편성 화면이 지도를 그리려면 이
 * 엔드포인트가 따로 필요하다.
 *
 * @param roadPath     도로 경로 좌표(순서 있음) — 지점이 2개 미만이면(정차지 0~1개 + 학원 좌표
 *                     부재) 경로라는 말이 성립하지 않아 빈 배열이다
 * @param fallbackUsed {@code true} 면 {@code roadPath} 가 직선거리 근사다
 * @param stops        정차 순서 — 상세 응답과 같은 모양({@link RouteStopResponse})을 재사용한다.
 *                     참조하는 승하차지 행이 없는 정차지는(데이터 정합 어긋남) 이 목록에서 빠진다
 */
public record RoutePathResponse(List<GeoPoint> roadPath, boolean fallbackUsed, List<RouteStopResponse> stops) {
}
