package src.backend.demo;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import src.backend.global.location.GeoPoint;
import src.backend.global.security.AuthUser;
import src.backend.location.command.RunPositionCommandService;
import src.backend.location.dto.RunPositionRequest;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.entity.Run;

/**
 * 운행 중인 데모 회차를 확정 노선의 도로 경로 위로 한 칸씩 옮기고 그 위치를 실 단말과 같은 명령 경로로 보낸다.
 * 회차별 커서와 송신 기록을 쥔다. 스프링 빈이 아니라 {@link DemoRunSimulator} 가 만들어 쓴다.
 */
final class DemoRoadPathMover {

    private static final Logger log = LoggerFactory.getLogger(DemoRunSimulator.class);

    /** 노선 한 바퀴를 도는 데 쓸 시간. 짧으면 순간이동처럼 보이고 길면 멈춘 것처럼 보인다. */
    private static final int LAP_MINUTES = 12;

    /** 회차별로 지금 road_path 의 몇 번째 좌표에 있는지. 재기동하면 처음으로 돌아간다(데모라 무방). */
    private final Map<Long, Integer> cursorByRun = new ConcurrentHashMap<>();

    /** 회차별로 시뮬레이터가 마지막으로 보낸 위치의 기록 시각 — 최신 위치가 이것과 다르면 다른 주체가 보낸 것이다. */
    private final Map<Long, OffsetDateTime> lastSentAtByRun = new ConcurrentHashMap<>();

    /**
     * 기사 단말이 위치를 보내는 회차 — 운행이 끝날 때까지 손대지 않는다. 학원 A 회차를 매니저 앱으로 출발시키면 기사 폰의
     * 실제 위치와 노선 위 가짜 위치가 번갈아 들어가 지도의 버스가 오가고 근접 · 출발 판정이 기사 조작과 무관해진다.
     */
    private final Set<Long> deviceRunIds = ConcurrentHashMap.newKeySet();

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunPositionCommandService runPositionCommandService;

    private final RunPositionStore runPositionStore;

    private final Clock clock;

    /** 그 회차에 배치된 기사를 요청 주체로 만드는 함수. */
    private final Function<Run, Optional<AuthUser>> driverOf;

    DemoRoadPathMover(ConfirmedRouteRepository confirmedRouteRepository, RouteVersionRepository routeVersionRepository,
            RunPositionCommandService runPositionCommandService, RunPositionStore runPositionStore, Clock clock,
            Function<Run, Optional<AuthUser>> driverOf) {
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.runPositionCommandService = runPositionCommandService;
        this.runPositionStore = runPositionStore;
        this.clock = clock;
        this.driverOf = driverOf;
    }

    /** 운행 중이 아닌 회차의 커서·송신 기록·단말 표시를 버린다. */
    void retainOnly(List<Long> movingIds) {
        cursorByRun.keySet().retainAll(movingIds);
        lastSentAtByRun.keySet().retainAll(movingIds);
        deviceRunIds.retainAll(movingIds);
    }

    /** 회차 하나를 도로 경로 위 다음 지점으로 옮기고 그 위치를 실 단말과 같은 명령 경로로 보낸다(CODE_CONVENTIONS §20.2, BR-101). */
    void advance(Run run) {
        if (deviceRunIds.contains(run.getId()) || sentByOther(run.getId())) {
            deviceRunIds.add(run.getId());
            return;
        }
        List<GeoPoint> path = roadPathOf(run.getId());
        if (path.size() < 2) {
            return;
        }
        GeoPoint point = path.get(nextCursor(run.getId(), path.size()));
        OffsetDateTime recordedAt = OffsetDateTime.now(clock);
        driverOf.apply(run).ifPresent(driver -> send(driver, run.getId(), point, recordedAt));
    }

    /** 커서를 한 걸음 앞으로 보내고(끝에 닿으면 처음으로) 그 값을 기억한다. */
    private int nextCursor(Long runId, int pathSize) {
        int step = Math.max(1, pathSize / (int) (LAP_MINUTES * 60_000L / DemoRunSimulator.TICK_MS));
        int cursor = (cursorByRun.getOrDefault(runId, 0) + step) % pathSize;
        cursorByRun.put(runId, cursor);
        return cursor;
    }

    /** 위치 하나를 정식 서비스로 보내고 성공하면 기록 시각을 남긴다 — 실패는 데모라 조용히 넘긴다. */
    private void send(AuthUser driver, Long runId, GeoPoint point, OffsetDateTime recordedAt) {
        try {
            runPositionCommandService.receive(driver, runId,
                    new RunPositionRequest(point.lat(), point.lng(), recordedAt, null, null));
            lastSentAtByRun.put(runId, recordedAt);
        } catch (RuntimeException e) {
            log.debug("[데모] 회차 {} 위치 송신 실패: {}", runId, e.getMessage());
        }
    }

    /**
     * 이 회차의 최신 위치를 시뮬레이터가 아닌 주체(기사 단말)가 보냈는가. 아직 한 번도 보내지 않은 회차는 판단하지 않는다 —
     * 재기동 직후 Redis 에 남은 자기 위치를 단말 위치로 오인하지 않기 위해서다.
     */
    private boolean sentByOther(Long runId) {
        OffsetDateTime lastSent = lastSentAtByRun.get(runId);
        if (lastSent == null) {
            return false;
        }
        return runPositionStore.find(runId)
                .map(latest -> !latest.recordedAt().truncatedTo(ChronoUnit.MILLIS)
                        .isEqual(lastSent.truncatedTo(ChronoUnit.MILLIS)))
                .orElse(false);
    }

    /** 확정 노선의 도로 경로. 아직 확정 전이거나 배포된 버전이 없으면 빈 목록이다. */
    private List<GeoPoint> roadPathOf(Long runId) {
        return confirmedRouteRepository.findById(runId)
                .map(ConfirmedRoute::getCurrentVersionId)
                .flatMap(routeVersionRepository::findById)
                .map(version -> version.getRoadPath() == null ? List.<GeoPoint>of() : version.getRoadPath())
                .orElseGet(List::of);
    }
}
