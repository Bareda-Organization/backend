package src.backend.run.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매니저 앱의 실시간 노선(API_SPEC §4.3 {@code GET /runs/{runId}/route}, RUN-03·M-08·M-09,
 * Ruling 205, Phase 9 목표 8) — 확정 노선(정차 순서)에 미승차(③구간) 반영 결과만 얹은 <b>표시용</b>
 * 뷰다. 미경유(skipped) 는 <b>표시만</b> 하고 재최적화·ETA 재계산은 하지 않는다(C-05, 사양 원문
 * "주행 판단은 기사").
 *
 * @param currentStop 현재 이동 중(도착 완료·다음 출발 전) 승하차지 — 도착 기록이 없으면 없다
 * @param nextStop 다음에 도착할 승하차지. skipped 는 건너뛰고 실제로 설 정차지를 돌려준다.
 *         {@code lat}·{@code lng} 는 외부 내비게이션 앱 콜백용이라 필수다(그래서 이 레코드 자체가
 *         {@link RouteStop} 을 재사용한다 — 그쪽도 항상 좌표를 갖는다)
 * @param skippedNotice 다음에 지나칠 미경유 승하차지 안내 한 줄("○○ 승하차지는 오늘 미경유")
 */
public record RunRouteResponse(List<RouteStop> stops, RouteStop currentStop, RouteStop nextStop,
        String skippedNotice) {

    /** 정차 항목 1건 — 학생 승하차지({@code Stop})든 강제 경유지({@code Waypoint})든 같은 모양으로 싣는다. */
    public record RouteStop(Long stopId, int seq, String name, String address, BigDecimal lat, BigDecimal lng,
            String change, long studentCount) {
    }
}
