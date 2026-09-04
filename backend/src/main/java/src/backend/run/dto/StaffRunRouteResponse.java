package src.backend.run.dto;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.run.dto.RunRouteResponse.RouteStop;

/**
 * 관계자용 확정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route}, RTE-02, F1 S3 목표
 * 11) — §4.3({@link RunRouteResponse})과 같은 정차 구조에 {@code route_version}·{@code published_at}·
 * {@code ack} 를 더한다. 매니저용 §4.3 은 배치된 회차로 범위가 한정(§1.5)돼 관계자가 부르면
 * {@code 403} 이라, 관계자는 이 응답으로 승인 화면·경유 지점 미리보기 밖에서 확정 노선을 본다.
 *
 * @param routeVersion 배포 버전 번호({@link src.backend.routing.entity.RouteVersion#getVersionNo()})
 * @param publishedAt 그 버전이 배포된 시각
 * @param ack 매니저 확인 여부 — {@code Assignment.ackedRouteVersionId == 현재 확정 버전}
 */
public record StaffRunRouteResponse(List<RouteStop> stops, RouteStop currentStop, RouteStop nextStop,
        String skippedNotice, int routeVersion, OffsetDateTime publishedAt, Ack ack) {

    public record Ack(boolean driver, boolean escort) {
    }
}
