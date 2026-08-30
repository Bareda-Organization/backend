package src.backend.routing.command;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;

/**
 * 강제 경유 지점 배포의 짧은 쓰기 트랜잭션(RTE-10, API_SPEC §5.15) — {@link WaypointCommandService}
 * 가 트랜잭션 밖에서 재최적화까지 마친 결과를 받아 "새 노선 버전 저장 + 현재 버전 포인터 전진 +
 * 경유 지점 상태 갱신 + 이벤트 발행" 만 한 트랜잭션으로 묶는다.
 *
 * <p>새 버전을 만들 뿐 확정 배치의 v1({@code RunConfirmationPersistence})처럼 회차 자체를 확정하지
 * 않는다 — 이 회차는 이미 확정된 상태에서 들어온다(§5.15 전제). {@code route_changed} 알림은
 * {@link RunRouteConfirmedEvent} 를 그대로 재사용한다({@code RunRouteConfirmedNotificationListener}
 * 가 이미 재확정 경로를 겨냥해 만들어져 있다, 그 클래스 자바독).
 */
@Component
@RequiredArgsConstructor
public class WaypointStore {

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final WaypointRepository waypointRepository;

    private final ApplicationEventPublisher eventPublisher;

    /** 새 경유 지점 추가를 배포한다 — {@code waypoint.apply()} 로 미리보기 단계를 벗어난다. */
    @Transactional
    public void deployAdd(Run run, Waypoint waypoint, RouteComputation computation, String fingerprint,
            Long createdBy, OffsetDateTime now) {
        deployNewVersion(run, computation, fingerprint, createdBy, now);
        waypoint.apply();
        waypointRepository.save(waypoint);
        eventPublisher.publishEvent(new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
    }

    /** 기존 경유 지점 제거를 배포한다 — {@code waypoint.markRemoved(now)} 로 이후 조회에서 뺀다. */
    @Transactional
    public void deployRemoval(Run run, Waypoint waypoint, RouteComputation computation, String fingerprint,
            Long createdBy, OffsetDateTime now) {
        deployNewVersion(run, computation, fingerprint, createdBy, now);
        waypoint.markRemoved(now);
        waypointRepository.save(waypoint);
        eventPublisher.publishEvent(new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
    }

    /**
     * 새 노선 버전을 저장하고 "현재 버전" 포인터를 그 행으로 옮긴다 — 추가·제거 양쪽이 공유하는
     * 절차다. 버전 번호는 이 트랜잭션 안에서 다시 읽은 현재 버전 기준으로 매긴다({@code +1}) — 밖에서
     * 미리 읽어 넘긴 값을 쓰면 그 사이 다른 배포가 끼어든 경합을 못 잡는다.
     */
    private RouteVersion deployNewVersion(Run run, RouteComputation computation, String fingerprint, Long createdBy,
            OffsetDateTime now) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));

        RouteVersion newVersion = RouteVersion.forConfirmedRoute(run.getId(), currentVersion.getVersionNo() + 1,
                RouteVersionSource.WAYPOINT, computation.estDurationMin(), computation.estDistanceKm(), now,
                fingerprint, computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), createdBy, now);
        routeVersionRepository.save(newVersion);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), newVersion.getId());
        runStopRepository.saveAll(runStopsOf(newVersion.getId(), computation));
        return newVersion;
    }

    /** {@code RunConfirmationPersistence.runStopsOf} 와 같은 계산(중복 헬퍼 관례, 그 클래스 자바독 참고). */
    private static List<RunStop> runStopsOf(Long versionId, RouteComputation computation) {
        List<OrderedStop> stops = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<RunStop> runStops = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            OrderedStop stop = stops.get(i);
            OffsetDateTime eta = etas.get(i);
            if (stop.stopId() != null) {
                runStops.add(RunStop.forStop(versionId, stop.stopId(), stop.seq(), eta));
            } else {
                runStops.add(RunStop.forWaypoint(versionId, stop.waypointId(), stop.seq(), eta));
            }
        }
        return runStops;
    }
}
