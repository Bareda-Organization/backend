package src.backend.routing.command;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
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
import src.backend.run.repository.RunRepository;

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

    private final RunRepository runRepository;

    /**
     * 새 미리보기 경유 지점 후보를 저장한다 — 기존 미배포 후보를 지우고 새 후보로 교체하는 두 쓰기를
     * 한 트랜잭션으로 묶는다({@code deleteAllUnappliedByRunIdAndAcademyId} 가 {@code @Modifying} 커스텀
     * 쿼리라 감싸는 트랜잭션이 없으면 {@code TransactionRequiredException} 이 난다 — 테스트가
     * {@code @Transactional} 이라 가려져 있었다). {@link WaypointCommandService#add} 가 이 회차의
     * 재최적화 계산(트랜잭션 밖이어야 하는 외부 지도 API 호출, 클래스 javadoc)을 시작하기 전에 부른다.
     */
    @Transactional
    public Waypoint saveCandidate(Run run, Waypoint waypoint) {
        waypointRepository.deleteAllUnappliedByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        return waypointRepository.save(waypoint);
    }

    /** 새 경유 지점 추가를 배포한다 — {@code waypoint.apply()} 로 미리보기 단계를 벗어난다. */
    @Transactional
    public void deployAdd(Run run, Waypoint waypoint, WaypointPreview preview, Long createdBy, OffsetDateTime now) {
        deployNewVersion(run, preview, createdBy, now);
        waypoint.apply();
        waypointRepository.save(waypoint);
        eventPublisher.publishEvent(new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
    }

    /** 기존 경유 지점 제거를 배포한다 — {@code waypoint.markRemoved(now)} 로 이후 조회에서 뺀다. */
    @Transactional
    public void deployRemoval(Run run, Waypoint waypoint, WaypointPreview preview, Long createdBy,
            OffsetDateTime now) {
        deployNewVersion(run, preview, createdBy, now);
        waypoint.markRemoved(now);
        waypointRepository.save(waypoint);
        eventPublisher.publishEvent(new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
    }

    /**
     * 새 노선 버전을 저장하고 "현재 버전" 포인터를 그 행으로 옮긴다 — 추가·제거 양쪽이 공유하는
     * 절차다. 버전 번호는 이 트랜잭션 안에서 다시 읽은 현재 버전 기준으로 매긴다({@code +1}) — 밖에서
     * 미리 읽어 넘긴 값을 쓰면 그 사이 다른 배포가 끼어든 경합을 못 잡는다.
     */
    private RouteVersion deployNewVersion(Run run, WaypointPreview preview, Long createdBy, OffsetDateTime now) {
        RouteComputation computation = preview.computation();
        String fingerprint = preview.fingerprint();
        assertStillDeployable(run, preview, now);
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));

        RouteVersion newVersion = RouteVersion.forConfirmedRoute(run.getId(), currentVersion.getVersionNo() + 1,
                RouteVersionSource.WAYPOINT, computation.estDurationMin(), computation.estDistanceKm(), now,
                fingerprint, computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), computation.roadPath(), createdBy, now);
        routeVersionRepository.save(newVersion);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), newVersion.getId());
        runStopRepository.saveAll(runStopsOf(newVersion.getId(), computation));
        return newVersion;
    }

    /**
     * 배포 트랜잭션 안에서 다시 확인한다(BR-021) — 회차 행을 잠근 뒤 ① 운행이 시작됐으면 {@code 403
     * CHANGE_WINDOW_CLOSED}(ARCHITECTURE §8.5 운행 시작과 동시에 노선 잠금) ② 계산의 바탕 판본이 지금 판본이
     * 아니면 {@code 409 PREVIEW_STALE} — 그 사이 끼어든 배포(승인·다른 경유 지점)를 모르는 계산이 그 위를 덮지
     * 않게 한다. 잠금이 같은 회차의 배포를 줄 세우므로 두 배포가 같은 판본 번호를 만들어 500 이 나지 않는다.
     */
    private void assertStillDeployable(Run run, WaypointPreview preview, OffsetDateTime now) {
        Run locked = runRepository.findLockedByIdAndAcademyId(run.getId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (ChangeWindowPolicy.segmentOf(locked, now) == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (!preview.baseVersionId().equals(currentVersionId)) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
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
