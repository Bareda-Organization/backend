package src.backend.location.proximity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.repository.RunRiderRepository;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.routing.domain.GeoPoint;
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

    private static final int NEXT_STOP_LIMIT = 1;

    /** 실패를 알릴 때 어느 판정인지. */
    public enum Judgment {
        /** 다음 미도착 정차지 300m 진입 판정(근접 알림). */
        APPROACH,
        /** 도착한 정차지에서 100m 밖 이탈 판정(출발 기록). */
        DEPARTURE
    }

    /** 읽기 트랜잭션이 알아낸, 이번 틱에 선점을 시도할 정차 항목 — 걸리지 않은 판정은 {@code null}. */
    private record Targets(RunStop approachStop, RunStop departureStop) {
        static final Targets NONE = new Targets(null, null);
    }

    private final ProximityJudge proximityJudge;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopDepartureService stopDepartureService;

    private final StopRepository stopRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    /**
     * 회차 1건의 근접·출발 두 판정을 한다 — 아래 중 하나라도 걸리면 그 판정은 아무 것도 하지 않고 조용히 지나간다(다음 틱에 다시
     * 대상이 되므로 예외로 알릴 필요가 없다).
     * <ul>
     *   <li>확정 노선이 없거나 현재 버전 포인터가 비어 있다(두 판정 모두)</li>
     *   <li>근접: 남은 미도착 정차지가 없다 · 다음 정차지가 300m 밖이다 · 선점이 0행이다(이미 다른 인스턴스가 먼저 선점 — 목표 15)</li>
     *   <li>출발: 도착했지만 출발 처리 전인 정차지가 없다 · 그 정차지에서 100m 밖이 아니다 · 선점이 0행이다</li>
     * </ul>
     *
     * @param position 스케줄러가 미리 읽은 이 회차의 최신 위치
     * @param onFailure 판정이 던진 예외를 받는다 — 판정마다 한 번씩 불린다. 다른 판정은 이어서 돈다
     */
    public void judgeRun(Long runId, Long academyId, RunPositionRedisValue position,
            BiConsumer<Judgment, Exception> onFailure) {
        Targets targets;
        try {
            targets = transactionTemplate.execute(status -> findTargets(runId, position, onFailure));
        } catch (Exception e) {
            // 트랜잭션 시작·확정 노선 조회가 실패했다 — 두 판정 모두 이번 틱에 돌지 못했다
            onFailure.accept(Judgment.APPROACH, e);
            onFailure.accept(Judgment.DEPARTURE, e);
            return;
        }
        if (targets.approachStop() != null) {
            runIsolated(Judgment.APPROACH, onFailure, () -> announceApproach(runId, academyId, targets.approachStop()));
        }
        if (targets.departureStop() != null) {
            runIsolated(Judgment.DEPARTURE, onFailure, () -> stopDepartureService.claimAndPublish(
                    targets.departureStop(), runId, academyId, OffsetDateTime.now(clock)));
        }
    }

    /** 확정 노선을 한 번 읽고 두 판정이 각자 걸렸는지 본다 — 읽기만 한다. 한 판정의 예외는 다른 판정을 막지 않는다. */
    private Targets findTargets(Long runId, RunPositionRedisValue position, BiConsumer<Judgment, Exception> onFailure) {
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return Targets.NONE;
        }
        Long versionId = confirmedRoute.get().getCurrentVersionId();
        GeoPoint bus = new GeoPoint(position.lat(), position.lng());
        RunStop approach = readIsolated(Judgment.APPROACH, onFailure, () -> approachTarget(versionId, bus));
        RunStop departure = readIsolated(Judgment.DEPARTURE, onFailure, () -> departureTarget(versionId, bus));
        return new Targets(approach, departure);
    }

    /** 다음 미도착 정차지가 300m 안이면 그 정차 항목, 아니면 {@code null}. */
    private RunStop approachTarget(Long versionId, GeoPoint bus) {
        List<RunStop> nextUnarrived = runStopRepository.findNextUnarrived(versionId, PageRequest.of(0, NEXT_STOP_LIMIT));
        if (nextUnarrived.isEmpty()) {
            return null;
        }
        RunStop nextStop = nextUnarrived.get(0);
        return stopRepository.findById(nextStop.getStopId())
                .filter(stop -> proximityJudge.isWithinThreshold(bus, new GeoPoint(stop.getLat(), stop.getLng())))
                .map(stop -> nextStop)
                .orElse(null);
    }

    /** 도착했지만 출발 처리 전인 첫 정차지에서 100m 밖이면 그 정차 항목, 아니면 {@code null}. */
    private RunStop departureTarget(Long versionId, GeoPoint bus) {
        List<RunStop> arrivedNotDeparted = runStopRepository.findFirstArrivedNotDeparted(versionId,
                PageRequest.of(0, NEXT_STOP_LIMIT));
        if (arrivedNotDeparted.isEmpty()) {
            return null;
        }
        RunStop targetStop = arrivedNotDeparted.get(0);
        return stopRepository.findById(targetStop.getStopId())
                .filter(stop -> proximityJudge.hasDeparted(bus, new GeoPoint(stop.getLat(), stop.getLng())))
                .map(stop -> targetStop)
                .orElse(null);
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
            List<Long> studentIds = runRiderRepository.findStudentIdsByRunIdAndStopIdExcludingAbsent(runId,
                    nextStop.getStopId());
            OffsetDateTime judgedAt = OffsetDateTime.now(clock);
            for (Long studentId : studentIds) {
                eventPublisher.publishEvent(
                        new RunApproachingStopEvent(runId, academyId, studentId, nextStop.getStopId(), judgedAt));
            }
        });
    }

    private RunStop readIsolated(Judgment judgment, BiConsumer<Judgment, Exception> onFailure, Supplier<RunStop> read) {
        try {
            return read.get();
        } catch (Exception e) {
            onFailure.accept(judgment, e);
            return null;
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
