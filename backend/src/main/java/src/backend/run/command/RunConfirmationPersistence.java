package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.repository.RunRepository;

/**
 * 확정 배치의 짧은 쓰기 트랜잭션 — {@link RunConfirmationService#confirmOne} 이 트랜잭션 밖에서
 * 계산까지 마친 결과를 받아 "확정 표시 + 4종 산출물 저장 + 이벤트 발행" 만 한 트랜잭션으로 묶는다.
 *
 * <p>별도 빈으로 분리한 이유는 self-invocation 때문이다 — {@code RunConfirmationService} 안의
 * 메서드로 두면 그 클래스 자신을 통한 호출이라 {@code @Transactional} 프록시를 거치지 않는다.
 *
 * <p><b>{@link #persist} 도중 어디서 예외가 나든 이 트랜잭션 전체가 롤백된다</b> — 그러면
 * {@link RunRepository#confirmIfIdle} 이 표시한 {@code idle → confirmed} 전이도 함께 취소되어 회차가
 * 자동으로 idle 로 되돌아간다(목표 5). 이벤트도 트랜잭션 커밋 안에서만 발행되므로 롤백된 확정에
 * 대해서는 발행되지 않는다({@link RunRouteConfirmedEvent} javadoc).
 */
@Component
@RequiredArgsConstructor
@Transactional
public class RunConfirmationPersistence {

    /** 확정 배치가 처음 만드는 노선 버전은 항상 v1 이다 — 재최적화(RTE-10)가 만드는 v2 이상은 이 클래스의 범위 밖이다. */
    private static final int INITIAL_VERSION_NO = 1;

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 회차를 확정하고 4종 산출물({@code confirmed_route}·{@code route_version}·{@code run_stop}·
     * {@code run_rider})을 저장한다.
     *
     * <p><b>{@code confirmIfIdle} 이 0행을 갱신하면 그 자리에서 조용히 반환한다</b>(목표 2) — 동시
     * 스레드 중 나중 것이 진 경우다. 이미 진 경쟁에서 계산 결과를 그대로 버리는 것이 맞다 — 먼저
     * 확정한 스레드의 결과와 이 결과가 같은 입력에서 나왔다는 보장이 없어(그 사이 명단이 바뀌었을 수
     * 있다), 대신 넣으면 먼저 커밋된 버전을 뒤엎는 꼴이 된다.
     */
    public void persist(Run run, RouteComputation computation, GeoPoint origin, GeoPoint destination,
            Weekday weekday, Map<Long, Long> studentStops, OffsetDateTime confirmedAt) {
        int updated = runRepository.confirmIfIdle(run.getId(), confirmedAt);
        if (updated == 0) {
            return;
        }

        ConfirmedRoute confirmedRoute = ConfirmedRoute.forRun(run.getId(), confirmedAt);
        confirmedRouteRepository.save(confirmedRoute);

        String fingerprint = RunConfirmationFingerprint.of(run.getAcademyId(), weekday, run.getDirection(),
                run.getDepartTime(), origin, destination, studentStops, List.of());
        RouteVersion version = RouteVersion.forConfirmedRoute(run.getId(), INITIAL_VERSION_NO,
                RouteVersionSource.CONFIRM_BATCH, computation.estDurationMin(), computation.estDistanceKm(),
                confirmedAt, fingerprint, computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), null, confirmedAt);
        routeVersionRepository.save(version);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), version.getId());

        runStopRepository.saveAll(runStopsOf(version.getId(), computation));
        runRiderRepository.saveAll(runRidersOf(run.getId(), studentStops, computation.unresolvedStudentIds()));

        eventPublisher.publishEvent(
                new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), confirmedAt));
    }

    /** {@code computation.stops()} 와 {@code etas()} 는 자리로 대응한다({@link RouteComputation} 계약). */
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

    /** 좌표를 얻지 못해 계산에서 분리된 학생({@code unresolvedStudentIds})은 명단에서도 뺀다(목표 2와 같은 근거). */
    private static List<RunRider> runRidersOf(Long runId, Map<Long, Long> studentStops,
            List<Long> unresolvedStudentIds) {
        List<RunRider> riders = new ArrayList<>(studentStops.size());
        for (Map.Entry<Long, Long> entry : studentStops.entrySet()) {
            Long studentId = entry.getKey();
            if (unresolvedStudentIds.contains(studentId)) {
                continue;
            }
            riders.add(RunRider.uponConfirmation(runId, studentId, entry.getValue()));
        }
        return riders;
    }
}
