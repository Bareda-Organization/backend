package src.backend.run.dto;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.routing.domain.GeoPoint;
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
 * @param roadPath 도로 경로 좌표(순서 있음, Ruling 309 · R15 T1) — {@code route_version.road_path} 를
 *                 그대로 낸다. T2(관계자 웹)가 이 이름 그대로 소비하는 고정 계약이다
 * @param fallbackUsed {@code true} 면 {@code roadPath} 가 직선거리 근사다 — 화면이 "근사 경로" 를
 *                     표시해야 사용자가 직선을 실제 경로로 오인하지 않는다(Ruling 309)
 * @param confirmed {@code false} 면 이 회차가 아직 확정 전(idle)이라 나머지 필드는 <b>고정 노선으로
 *                  낸 예정 경로</b>다(Ruling 321) — {@code routeVersion}·{@code publishedAt}·{@code ack}
 *                  는 그 상태에서 의미가 없어 각각 0·{@code null}·{@code false/false} 로 채운다. 예정
 *                  경로는 확정 시점의 그날 명단으로 다시 계산되므로 확정본과 달라질 수 있다 — 화면은
 *                  이 값을 "확정된 경로"로 읽히게 두면 안 된다
 */
public record StaffRunRouteResponse(List<RouteStop> stops, RouteStop currentStop, RouteStop nextStop,
        String skippedNotice, int routeVersion, OffsetDateTime publishedAt, Ack ack, List<GeoPoint> roadPath,
        boolean fallbackUsed, boolean confirmed) {

    public record Ack(boolean driver, boolean escort) {
    }
}
