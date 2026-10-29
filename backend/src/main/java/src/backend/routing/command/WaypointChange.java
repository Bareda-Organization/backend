package src.backend.routing.command;

import java.util.List;

import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.Waypoint;
import src.backend.run.entity.Run;

/**
 * 경유 지점 지정·제거 한 건이 재최적화 계산에서 배포까지 주고받는 값 묶음(RTE-10) — 대상 회차·경유 지점·문맥과
 * 이 변경을 반영한 고정 지점 목록이다. {@link WaypointPreviewPublisher} 와 {@link WaypointResponseAssembler} 가 같은 값을 받는다.
 */
public record WaypointChange(Run run, Waypoint waypoint, RouteContext ctx, List<FixedStop> fixedStops) {
}
