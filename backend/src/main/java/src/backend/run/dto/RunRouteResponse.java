package src.backend.run.dto;

import java.math.BigDecimal;
import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 매니저 앱의 실시간 노선(API_SPEC §4.3 {@code GET /runs/{runId}/route}, RUN-03·M-08·M-09,
 * Ruling 205, Phase 9 목표 8) — 확정 노선(정차 순서)에 미승차(③구간) 반영 결과만 얹은 <b>표시용</b>
 * 뷰다. 미경유(skipped) 는 <b>표시만</b> 하고 재최적화·ETA 재계산은 하지 않는다(C-05, 사양 원문
 * "주행 판단은 기사").
 *
 * @param currentStop 현재 이동 중(도착 완료·다음 출발 전) 승하차지 — 도착 기록이 없으면 없다
 * @param nextStop 다음에 도착할 승하차지. skipped 는 건너뛰고 실제로 설 정차지를 돌려준다.
 *         {@code lat}·{@code lng} 는 외부 내비게이션 앱 콜백용이라 필수다 — 이 레코드 자체는
 *         {@link RouteStop} 을 재사용하지만, {@link RouteStop} 일반은 좌표가 없을 수 있다(배포 후
 *         제거된 경유 지점이 {@code stops[]} 전체 목록엔 그대로 남는다, API_SPEC §1.13). {@code
 *         next_stop} 만은 {@code RunRouteQueryService#hasResolvedTarget} 이 좌표 없는 항목을
 *         건너뛰어 이 필드의 필수 약속을 지킨다
 * @param skippedNotice 다음에 지나칠 미경유 승하차지 안내 한 줄("○○ 승하차지는 오늘 미경유")
 * @param roadPath 확정 노선의 도로 경로 좌표(순서 있음) — 기사 운행 화면 지도가 그린다(Ruling 365).
 *                 {@code route_version.road_path} 를 그대로 내고, 비어 있는 옛 버전이면 빈 목록이다.
 *                 §5.19 {@link StaffRunRouteResponse#roadPath()} 와 같은 이름·모양
 * @param fallbackUsed {@code true} 면 {@code roadPath} 가 직선거리 근사다 — 화면이 "근사 경로" 를 표시해
 *                     직선을 실제 경로로 오인하지 않게 한다(Ruling 309)
 */
public record RunRouteResponse(List<RouteStop> stops, RouteStop currentStop, RouteStop nextStop,
        String skippedNotice, List<GeoPoint> roadPath, boolean fallbackUsed) {

    /**
     * 정차 항목 1건 — 학생 승하차지({@code Stop})든 강제 경유지({@code Waypoint})든 같은 모양으로
     * 싣는다. {@code lat}·{@code lng} 는 <b>이 레코드 단독으로는 필수가 아니다</b> — 배포 후 제거된
     * 경유 지점을 가리키는 행은 좌표가 전부 {@code null} 이다(API_SPEC §1.13 "등급이 다른 것"). 그
     * null 을 신뢰해도 되는 자리는 {@code stops[]} 전체 목록뿐이고, {@code next_stop} 은
     * {@code RunRouteQueryService} 가 그런 행을 걸러내 이 레코드를 재사용하면서도 좌표를 보장한다.
     *
     * <p>{@code stopId} 는 정차 항목 id({@code run_stop.id}) — 도착 처리(§4.5)가 받는 값과 같다(Ruling 327).
     * 확정 전 예정 경로(§5.19 {@code confirmed=false})는 정차 항목이 아직 없어 승하차지 id 를 싣는다.
     * {@code isDestination} 은 등원 회차의 마지막 학원 항목에서만 {@code true} 다.
     *
     * <p>{@code isWaypoint} 는 강제 경유 지점(§5.15) 항목에서만 {@code true} 다(Ruling 400) — 경유 지점은
     * 이름·좌표만 있고 태울 학생이 없어 승하차지와 모양이 같아 다른 필드로는 가를 수 없고, 지도가
     * 승하차지와 다른 마커로 그리려면 이 값이 필요하다. 배포 후 제거돼 좌표가 없는 행도 대상이면 {@code true}.
     */
    public record RouteStop(Long stopId, int seq, String name, String address, BigDecimal lat, BigDecimal lng,
            String change, long studentCount, boolean isDestination, boolean isWaypoint) {
    }
}
