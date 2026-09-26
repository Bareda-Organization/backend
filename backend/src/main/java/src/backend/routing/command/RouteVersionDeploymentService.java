package src.backend.routing.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;

/**
 * 확정 노선({@code confirmed_route}·{@code route_version}·{@code run_stop}) 쓰기의 유일한 진입점
 * (BR-094·095, `ARCHITECTURE §3.3` — "다른 모듈 소유 테이블은 그 모듈의 진입점을 거쳐서만 쓴다").
 *
 * <p>이 세 테이블은 {@code routing} 소유이지만, 확정 배치(v1, {@code run} 소유)·②구간 승인 배포(v2+,
 * {@code request} 소유)·경유 지점 배포(v2+, {@code routing} 자신)가 각자 이 저장소들을 직접
 * {@code save}/{@code saveAll} 하던 것을 이 클래스로 모았다({@code WaypointStore}·
 * {@code RunConfirmationPersistence}·{@code ChangeRequestDecisionService} 세 곳이 거의 같은 코드를
 * 중복 소유했다, 2026-09-25 검사 W02-12·W09-10·W09-11). 계산 로직(재최적화·지문)은 그대로 각 호출부에
 * 남고, 이 클래스는 "그 계산 결과를 어떻게 영속화하는가" 만 담당한다.
 */
@Component
@RequiredArgsConstructor
public class RouteVersionDeploymentService {

    /** 확정 배치가 처음 만드는 노선 버전은 항상 v1 이다 — 재최적화(RTE-10)가 만드는 v2 이상은 {@link #deployNewVersion} 의 범위다. */
    private static final int INITIAL_VERSION_NO = 1;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    /**
     * 회차가 처음 확정될 때(v1)만 호출된다 — {@code confirmed_route} 행 자체가 아직 없다.
     * {@code run} 소유({@code RunConfirmationPersistence#persist})가 유일한 호출부다.
     */
    @Transactional
    public RouteVersion confirmInitial(Run run, RouteComputation computation, String fingerprint,
            OffsetDateTime confirmedAt) {
        ConfirmedRoute confirmedRoute = ConfirmedRoute.forRun(run.getId(), confirmedAt);
        confirmedRouteRepository.save(confirmedRoute);
        RouteVersion version = RouteVersion.forConfirmedRoute(run.getId(), INITIAL_VERSION_NO,
                RouteVersionSource.CONFIRM_BATCH, computation.estDurationMin(), computation.estDistanceKm(),
                confirmedAt, fingerprint, computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), computation.roadPath(), null, confirmedAt);
        routeVersionRepository.save(version);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), version.getId());
        runStopRepository.saveAll(RunStop.listOf(version.getId(), computation, run.getDirection()));
        return version;
    }

    /**
     * 이미 확정된 노선에 새 버전을 배포한다(v2+) — 경유 지점 배포({@code routing} 자신)와 ②구간 승인
     * 배포({@code request} 소유)가 공유한다. 버전 번호는 이 트랜잭션 안에서 다시 읽은 현재 버전 기준으로
     * 매긴다(+1) — 밖에서 미리 읽어 넘긴 값을 쓰면 그 사이 다른 배포가 끼어든 경합을 못 잡는다.
     *
     * @param watchedStopId 배포로 그 승하차지가 빠지는지 확인하고 싶을 때만 준다(②구간 승인의 "잔여
     *                       0명이면 승하차지 제거" 판정, {@code null} 이면 검사하지 않고 결과의
     *                       {@link DeployedVersion#stopRemoved()} 는 항상 {@code false}) — 경유 지점
     *                       배포는 이 판정이 필요 없다.
     */
    @Transactional
    public DeployedVersion deployNewVersion(Run run, RouteComputation computation, String fingerprint,
            RouteVersionSource source, Long createdBy, OffsetDateTime now, Long watchedStopId) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));

        boolean stopRemoved = watchedStopId != null
                && stopRemoved(currentVersionId, run.getAcademyId(), computation, watchedStopId);

        RouteVersion newVersion = RouteVersion.forConfirmedRoute(run.getId(), currentVersion.getVersionNo() + 1,
                source, computation.estDurationMin(), computation.estDistanceKm(), now, fingerprint,
                computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), computation.roadPath(), createdBy, now);
        routeVersionRepository.save(newVersion);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), newVersion.getId());
        runStopRepository.saveAll(RunStop.listOf(newVersion.getId(), computation, run.getDirection()));
        return new DeployedVersion(newVersion, stopRemoved);
    }

    /** 지금 판본에 있던 승하차지가 이번 계산 결과에는 없는지 — "잔여 0명이면 승하차지 제거" 판정. */
    private boolean stopRemoved(Long currentVersionId, Long academyId, RouteComputation computation,
            Long watchedStopId) {
        List<RunStop> beforeRunStops = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId);
        return beforeRunStops.stream().anyMatch(rs -> watchedStopId.equals(rs.getStopId()))
                && computation.stops().stream().noneMatch(os -> watchedStopId.equals(os.stopId()));
    }

    /** {@link #deployNewVersion} 의 결과 — 새로 배포된 버전과(있으면) 대상 승하차지 제거 여부. */
    public record DeployedVersion(RouteVersion newVersion, boolean stopRemoved) {
    }
}
