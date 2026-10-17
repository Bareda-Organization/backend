package src.backend.location.proximity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.global.location.RunRiderDirectory;
import src.backend.global.location.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.StopDepartureService;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 회차 1건의 근접·출발 두 판정 — {@link #judgeRun} 이 위치(스케줄러가 회차 묶음당 Redis {@code MGET} 한 번으로 미리 읽어 넘긴다)로
 * 두 판정을 <b>한 트랜잭션</b>에서 같이 읽는다(R46-LATERBE L5, Ruling 672). 확정 노선 조회 한 번을 두 판정이 나눠 쓰고, 틱마다 회차당
 * 트랜잭션이 2개에서 1개로 준다. 근접 판정은 다음 미도착 정차지가 300m 안인지(NTF-04, Ruling 207), 출발 판정은 도착했지만 아직 출발
 * 처리되지 않은 정차지에서 100m 밖으로 벗어났는지(Ruling 307, R14-T2 목표 6a)를 본다.
 *
 * <p><b>읽기와 선점을 가른다</b> — 읽기 트랜잭션은 "판정이 걸렸는가" 만 알아낸다. 걸린 판정(정차지당 한 번뿐이라 드물다)은 각자
 * 별도 트랜잭션에서 선점({@link RunStopRepository#claimProximityNotice} · {@link StopDepartureService#claimAndPublish}) 하고
 * 이벤트를 발행한다. 선점 뒤 이벤트 발행이 실패하면 <b>그 판정의 트랜잭션만</b> 롤백돼 선점도 함께 취소되고(다음 틱에 재시도) 다른
 * 판정은 영향이 없다 — 한 트랜잭션에 합치면 한 판정의 롤백이 다른 판정의 선점까지 되돌린다. 판정이 던진 예외는 호출자가 넘긴
 * {@code onFailure} 로 알리고 다른 판정은 이어서 돈다(BR-235 — 판정별 격리).
 *
 * <p><b>위치 읽기는 이 클래스 밖, 트랜잭션 밖이다(BR-166, W12-01)</b> — Redis 가 답하지 않는 동안 DB 커넥션을 쥐면 운행 중 회차 수만큼
 * 커넥션이 묶인다. Redis 가 실패하면 {@code RunPositionStore.findAll} 이 묶음 전체를 {@code run_position} 최신 행 1쿼리로 대체한다
 * (Ruling 624).
 */
@Component
@RequiredArgsConstructor
public class ProximityNotificationService {

    /** 실패를 알릴 때 어느 판정인지. */
    public enum Judgment {
        /** 다음 미도착 정차지 300m 진입 판정(근접 알림). */
        APPROACH,
        /** 도착한 정차지에서 100m 밖 이탈 판정(출발 기록). */
        DEPARTURE
    }

    /** 읽기 트랜잭션이 알아낸, 이번 틱에 선점을 시도할 정차 항목 — 걸리지 않은 판정은 {@code null}. */
    public record RunTargets(RunStop approachStop, RunStop departureStop, RunStop nearObservedStop) {
        /** 어느 판정도 걸리지 않은 회차. */
        public static final RunTargets NONE = new RunTargets(null, null, null);
    }

    /** 출발 판정의 읽기 결과 — 출발로 볼 정차 항목, 또는 이번 위치로 "100m 안 관측" 을 처음 기록할 정차 항목(둘 다 없을 수 있다). */
    private record DepartureRead(RunStop departureStop, RunStop nearObservedStop) {
        static final DepartureRead NONE = new DepartureRead(null, null);
    }

    /** 판정이 던진 예외를 받는다 — 회차 · 판정마다 한 번씩 불린다. */
    @FunctionalInterface
    public interface FailureSink {
        void accept(Long runId, Judgment judgment, Exception e);
    }

    private final ProximityJudge proximityJudge;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderDirectory runRiderDirectory;

    private final StopDepartureService stopDepartureService;

    private final StopRepository stopRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    /**
     * 한 묶음(회차 수와 무관하게 SQL 4건)의 두 판정 재료를 <b>읽기 트랜잭션 하나</b>로 한꺼번에 읽는다(DA-1, Ruling 899) — 확정 노선 ·
     * 다음 미도착 정차 항목 · 도착한 정차 항목 · 정차지 좌표를 회차마다 따로 읽지 않는다. 읽기만 한다. 회차 하나의 판정 계산이 던진 예외는
     * {@code onFailure} 로 알리고 다른 회차는 이어서 본다. 읽기 자체가 실패하면 묶음 전체가 두 판정 모두 실패로 알려지고 빈 결과다.
     *
     * @param positions 회차 id → 스케줄러가 미리 읽은 그 회차의 최신 위치
     */
    public Map<Long, RunTargets> readTargets(Map<Long, RunPositionRedisValue> positions, FailureSink onFailure) {
        if (positions.isEmpty()) {
            return Map.of();
        }
        try {
            return transactionTemplate.execute(status -> targetsOf(positions, onFailure));
        } catch (Exception e) {
            // 트랜잭션 시작 · 조회가 실패했다 — 이 묶음은 두 판정 모두 이번 틱에 돌지 못했다
            positions.keySet().forEach(runId -> {
                onFailure.accept(runId, Judgment.APPROACH, e);
                onFailure.accept(runId, Judgment.DEPARTURE, e);
            });
            return Map.of();
        }
    }

    private Map<Long, RunTargets> targetsOf(Map<Long, RunPositionRedisValue> positions, FailureSink onFailure) {
        Map<Long, Long> versionIdByRun = new HashMap<>();
        for (ConfirmedRoute route : confirmedRouteRepository.findAllByRunIdIn(positions.keySet())) {
            if (route.getCurrentVersionId() != null) {
                versionIdByRun.put(route.getRunId(), route.getCurrentVersionId());
            }
        }
        if (versionIdByRun.isEmpty()) {
            return Map.of();
        }
        List<Long> versionIds = versionIdByRun.values().stream().distinct().toList();
        Map<Long, RunStop> nextUnarrivedByVersion = byVersion(runStopRepository.findNextUnarrivedOfVersions(versionIds));
        Map<Long, RunStop> arrivedNotDepartedByVersion = byVersion(
                runStopRepository.findFirstArrivedNotDepartedOfVersions(versionIds));
        Set<Long> stopIds = new HashSet<>();
        Stream.concat(nextUnarrivedByVersion.values().stream(), arrivedNotDepartedByVersion.values().stream())
                .forEach(runStop -> stopIds.add(runStop.getStopId()));
        Map<Long, Stop> stopsById = stopRepository.findAllByIdIn(stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));

        Map<Long, RunTargets> targets = new HashMap<>();
        versionIdByRun.forEach((runId, versionId) -> {
            RunPositionRedisValue position = positions.get(runId);
            GeoPoint bus = new GeoPoint(position.lat(), position.lng());
            RunStop approach = readIsolated(runId, Judgment.APPROACH, onFailure,
                    () -> approachTarget(nextUnarrivedByVersion.get(versionId), stopsById, bus), null);
            DepartureRead departure = readIsolated(runId, Judgment.DEPARTURE, onFailure,
                    () -> departureTarget(arrivedNotDepartedByVersion.get(versionId), stopsById, bus, position),
                    DepartureRead.NONE);
            targets.put(runId, new RunTargets(approach, departure.departureStop(), departure.nearObservedStop()));
        });
        return targets;
    }

    private static Map<Long, RunStop> byVersion(List<RunStop> runStops) {
        return runStops.stream().collect(Collectors.toMap(RunStop::getRouteVersionId, Function.identity(),
                (first, duplicate) -> first));
    }

    /**
     * 회차 1건 단독 판정 — 그 회차의 재료를 읽고({@link #readTargets}) 바로 {@link #judgeRun(Long, Long, RunPositionRedisValue,
     * RunTargets, BiConsumer)} 를 부른다. 운영 틱은 묶음으로 읽고 아래 오버로드를 쓴다.
     */
    public void judgeRun(Long runId, Long academyId, RunPositionRedisValue position,
            BiConsumer<Judgment, Exception> onFailure) {
        RunTargets targets = readTargets(Map.of(runId, position), (id, judgment, e) -> onFailure.accept(judgment, e))
                .getOrDefault(runId, RunTargets.NONE);
        judgeRun(runId, academyId, position, targets, onFailure);
    }

    /**
     * 회차 1건의 근접·출발 두 판정을 한다 — 읽어 온 {@code targets} 중 걸린 판정만 선점·발행한다. 아래 중 하나라도 걸리면 그 판정은 아무 것도
     * 하지 않고 조용히 지나간다(다음 틱에 다시 대상이 되므로 예외로 알릴 필요가 없다).
     * <ul>
     *   <li>확정 노선이 없거나 현재 버전 포인터가 비어 있다(두 판정 모두)</li>
     *   <li>근접: 남은 미도착 정차지가 없다 · 다음 정차지가 300m 밖이다 · 선점이 0행이다(이미 다른 인스턴스가 먼저 선점 — 목표 15)</li>
     *   <li>출발: 도착했지만 출발 처리 전인 정차지가 없다 · 그 정차지에서 100m 밖이 아니다 · 도착 뒤 그 정차지 100m 안에서 받은 위치가 아직 없다(Ruling 875 — 이 위치를 받으면 관측만 기록하고 출발은 다음 틱) · 선점이 0행이다</li>
     * </ul>
     *
     * @param targets {@link #readTargets} 가 읽은 이 회차의 판정 재료
     * @param onFailure 판정이 던진 예외를 받는다 — 판정마다 한 번씩 불린다. 다른 판정은 이어서 돈다
     */
    public void judgeRun(Long runId, Long academyId, RunPositionRedisValue position, RunTargets targets,
            BiConsumer<Judgment, Exception> onFailure) {
        if (targets.approachStop() != null) {
            runIsolated(Judgment.APPROACH, onFailure, () -> announceApproach(runId, academyId, targets.approachStop()));
        }
        if (targets.nearObservedStop() != null) {
            runIsolated(Judgment.DEPARTURE, onFailure, () -> transactionTemplate.executeWithoutResult(
                    status -> runStopRepository.claimNearObservation(targets.nearObservedStop().getId(),
                            OffsetDateTime.now(clock))));
        }
        if (targets.departureStop() != null) {
            runIsolated(Judgment.DEPARTURE, onFailure, () -> stopDepartureService.claimAndPublish(
                    targets.departureStop(), runId, academyId, OffsetDateTime.now(clock)));
        }
    }

    /** 다음 미도착 정차지가 300m 안이면 그 정차 항목, 아니면 {@code null}. */
    private RunStop approachTarget(RunStop nextStop, Map<Long, Stop> stopsById, GeoPoint bus) {
        if (nextStop == null) {
            return null;
        }
        Stop stop = stopsById.get(nextStop.getStopId());
        return stop != null && proximityJudge.isWithinThreshold(bus, new GeoPoint(stop.getLat(), stop.getLng()))
                ? nextStop
                : null;
    }

    /**
     * 도착했지만 출발 처리 전인 첫 정차지의 출발 판정(Ruling 307·875). 도착 처리 뒤 그 정차지 100m 안에서 받은 위치가 아직 없으면(
     * {@code near_observed_at} 이 비었으면) 100m 밖 위치는 출발이 아니다 — 안쪽이면서 도착 처리 <b>뒤</b>({@code receivedAt} 기준)에 받은
     * 위치일 때만 "관측" 으로 기록한다. 관측이 끝내 없는 정차지는 다음 승하차지 도착·운행 종료의 강제 발송(Ruling 854)이 출발 처리한다.
     */
    private DepartureRead departureTarget(RunStop targetStop, Map<Long, Stop> stopsById, GeoPoint bus,
            RunPositionRedisValue position) {
        if (targetStop == null) {
            return DepartureRead.NONE;
        }
        Stop stop = stopsById.get(targetStop.getStopId());
        if (stop == null) {
            return DepartureRead.NONE;
        }
        boolean outside = proximityJudge.hasDeparted(bus, new GeoPoint(stop.getLat(), stop.getLng()));
        if (targetStop.getNearObservedAt() != null) {
            return outside ? new DepartureRead(targetStop, null) : DepartureRead.NONE;
        }
        boolean receivedAfterArrival = !position.receivedAt().isBefore(targetStop.getArrivedAt());
        return !outside && receivedAfterArrival ? new DepartureRead(null, targetStop) : DepartureRead.NONE;
    }

    /**
     * 근접 선점 → 학생별 이벤트 발행을 한 트랜잭션으로 — 발행(동기 리스너가 알림을 아웃박스에 적재)이 실패하면 선점도 롤백돼
     * 다음 틱에 다시 판정한다. 선점이 0행이면 이미 다른 인스턴스가 먼저 선점한 것이다.
     */
    private void announceApproach(Long runId, Long academyId, RunStop nextStop) {
        transactionTemplate.executeWithoutResult(status -> {
            int claimed = runStopRepository.claimProximityNotice(nextStop.getId(), OffsetDateTime.now(clock));
            if (claimed == 0) {
                return;
            }
            List<Long> studentIds = runRiderDirectory.studentIdsAtStopExcludingAbsent(runId, nextStop.getStopId());
            OffsetDateTime judgedAt = OffsetDateTime.now(clock);
            for (Long studentId : studentIds) {
                eventPublisher.publishEvent(
                        new RunApproachingStopEvent(runId, academyId, studentId, nextStop.getStopId(), judgedAt));
            }
        });
    }

    private <T> T readIsolated(Long runId, Judgment judgment, FailureSink onFailure, Supplier<T> read, T onError) {
        try {
            return read.get();
        } catch (Exception e) {
            onFailure.accept(runId, judgment, e);
            return onError;
        }
    }

    private void runIsolated(Judgment judgment, BiConsumer<Judgment, Exception> onFailure, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            onFailure.accept(judgment, e);
        }
    }
}
