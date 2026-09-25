package src.backend.routing.command;

import java.util.List;

import src.backend.global.common.enums.Weekday;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.DailyRoster;

/**
 * 재최적화 한 번에 필요한 문맥(RTE-10, API_SPEC §5.15) — {@link WaypointCommandService} 의 add·remove
 * 가 {@link RouteContextAssembler} 로 이 조립을 공유한다(BR-101, W02-17 SRP 분리).
 */
public record RouteContext(Weekday weekday, OriginDestination originDestination, RouteVersion currentVersion,
        List<RunStop> beforeRunStops, DailyRoster roster, List<Waypoint> appliedWaypoints) {
}
