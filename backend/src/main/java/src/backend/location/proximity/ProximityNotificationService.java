package src.backend.location.proximity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.repository.RunRiderRepository;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.StopDepartureService;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 회차 1건의 근접·출발 두 판정 — {@link #judgeOne}(NTF-04, Ruling 207)은 위치를 읽은 뒤 다음 미도착
 * 정차지 조회 → 거리 판정 → 선점 → 이벤트 발행까지를 <b>한 트랜잭션</b>으로 묶는다(목표 13·15).
 * {@link #judgeDeparture}(Ruling 307, R14-T2 목표 6a)는 같은 재료(위치 읽기·거리 판정·선점 형태)를
 * 재사용해 도착된 정차지의 출발 시점만 독립적으로 기록한다 — 두 판정이 서로 다른 정차 항목을
 * 보므로 트랜잭션도 나눈다({@link #judgeDeparture} 자체 주석 참고).
 *
 * <p><b>위치 읽기는 트랜잭션 밖이다(BR-166, W12-01)</b> — 트랜잭션을 연 뒤(커넥션을 빌린 뒤) Redis 를 읽으면
 * Redis 가 답하지 않는 동안 운행 중 회차마다 DB 커넥션이 1개씩 묶인다. 그래서 읽기를 먼저 하고 나머지만
 * {@link TransactionTemplate} 으로 묶는다. Redis 가 실패하면 예외가 그대로 나가 스케줄러가 그 회차의 이번 틱을
 * 건너뛴다(대체 조회 부재 — TECH_DECISIONS §14.2). 선점({@link RunStopRepository#claimProximityNotice}) 뒤 이벤트
 * 발행이 실패하면 그 트랜잭션 전체가 롤백돼 선점도 함께 취소된다 — 그 근거는 그 메서드의 주석에 있다.
 */
@Component
@RequiredArgsConstructor
public class ProximityNotificationService {

    private static final int NEXT_STOP_LIMIT = 1;

    private final RunPositionStore runPositionStore;

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
     * 회차 1건을 판정한다 — 아래 중 하나라도 걸리면 아무 것도 하지 않고 조용히 반환한다(다음 틱에
     * 다시 판정 대상이 되므로 예외로 알릴 필요가 없다).
     * <ul>
     *   <li>위치가 아직 없다(첫 위치 수신 전)</li>
     *   <li>확정 노선이 없거나 현재 버전 포인터가 비어 있다</li>
     *   <li>남은 미도착 정차지가 없다(전 구간 도착 완료)</li>
     *   <li>다음 정차지가 300m 밖이다</li>
     *   <li>{@link RunStopRepository#claimProximityNotice} 가 0행을 갱신했다(이미 다른 스케줄러
     *       인스턴스가 먼저 선점 — 목표 15)</li>
     * </ul>
     *
     * <p>CODE_CONVENTIONS §20.2 크기 신호(20줄 초과) — 굳이 나누지 않는다. {@link #judgeDeparture} 와 앞부분(위치·노선·
     * 정차지 조회) 모양이 비슷하지만 CODE_CONVENTIONS §20.3-4 는 중복을 3번째 등장에서 추출하라고 하고, 이건 2벌뿐이다.
     * 조기 반환 5개가 순서대로 이어지는 한 트랜잭션의 서술이라 쪼개면 오히려 흐름이 파일 사이로 흩어진다.
     */
    public void judgeOne(Long runId, Long academyId) {
        runPositionStore.findCached(runId).ifPresent(position -> transactionTemplate.executeWithoutResult(
                status -> judgeOneAt(runId, academyId, position)));
    }

    private void judgeOneAt(Long runId, Long academyId, RunPositionRedisValue position) {
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return;
        }

        List<RunStop> nextUnarrived = runStopRepository.findNextUnarrived(confirmedRoute.get().getCurrentVersionId(),
                PageRequest.of(0, NEXT_STOP_LIMIT));
        if (nextUnarrived.isEmpty()) {
            return;
        }
        RunStop nextStop = nextUnarrived.get(0);

        Optional<Stop> stop = stopRepository.findById(nextStop.getStopId());
        if (stop.isEmpty()) {
            return;
        }

        GeoPoint busPosition = new GeoPoint(position.lat(), position.lng());
        GeoPoint stopPosition = new GeoPoint(stop.get().getLat(), stop.get().getLng());
        if (!proximityJudge.isWithinThreshold(busPosition, stopPosition)) {
            return;
        }

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
    }

    /**
     * 회차 1건의 출발 판정(Ruling 307, R14-T2 목표 6a) — 도착했지만 아직 출발 처리되지 않은 정차지가
     * 있고 버스가 그 정차지에서 100m 밖으로 벗어났으면 {@code departed_at} 을 선점 기록한다.
     * 되돌리기 제한({@code BoardingCommandService#assertNotDeparted}, Ruling 305) 의 유일한 재료다.
     *
     * <p>{@link #judgeOne} 과 트랜잭션을 공유하지 않는다 — 서로 다른 정차 항목을 판정하는 독립된
     * 사건이라, 한쪽이 실패해도 다른 쪽 선점을 되돌릴 이유가 없다(스케줄러가 각각 별도로 부른다).
     * 위치를 다시 읽는 이유도 같다 — 로컬 Redis 단건 읽기라 {@link #judgeOne} 과 값을 공유해도 얻는
     * 이득이 크지 않고, 두 판정의 독립성을 지키는 값이 더 크다.
     *
     * <p>선점에 성공하면(1행 갱신) {@link StopDepartureService#claimAndPublish} 가
     * {@code StopDepartedEvent} 를 발행한다 — 그 승하차지의 확정 결과를 학생별로 통지하는 재료다
     * (Ruling 308, docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 2). {@code academyId} 는 그 이벤트에 실어 보낸다.
     *
     * <p>CODE_CONVENTIONS §20.2 크기 신호 — 나누지 않는 이유는 {@link #judgeOne} 주석과 같다(조기 반환 체인, 중복은
     * 2벌뿐이라 CODE_CONVENTIONS §20.3-4 의 3번째 추출 기준 미달). 위치 읽기가 트랜잭션 밖인 이유는 클래스 자바독(BR-166).
     */
    public void judgeDeparture(Long runId, Long academyId) {
        runPositionStore.findCached(runId).ifPresent(position -> transactionTemplate.executeWithoutResult(
                status -> judgeDepartureAt(runId, academyId, position)));
    }

    private void judgeDepartureAt(Long runId, Long academyId, RunPositionRedisValue position) {
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return;
        }

        List<RunStop> arrivedNotDeparted = runStopRepository.findFirstArrivedNotDeparted(
                confirmedRoute.get().getCurrentVersionId(), PageRequest.of(0, NEXT_STOP_LIMIT));
        if (arrivedNotDeparted.isEmpty()) {
            return;
        }
        RunStop targetStop = arrivedNotDeparted.get(0);

        Optional<Stop> stop = stopRepository.findById(targetStop.getStopId());
        if (stop.isEmpty()) {
            return;
        }

        GeoPoint busPosition = new GeoPoint(position.lat(), position.lng());
        GeoPoint stopPosition = new GeoPoint(stop.get().getLat(), stop.get().getLng());
        if (!proximityJudge.hasDeparted(busPosition, stopPosition)) {
            return;
        }

        stopDepartureService.claimAndPublish(targetStop, runId, academyId, OffsetDateTime.now(clock));
    }
}
