package src.backend.location.proximity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.repository.RunRiderRepository;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 회차 1건의 근접 알림(NTF-04) 판정 — 위치 읽기 → 다음 미도착 정차지 조회 → 거리 판정 → 선점 →
 * 이벤트 발행까지를 <b>한 트랜잭션</b>으로 묶는다(목표 13·15).
 *
 * <p>{@code RunConfirmationService} 처럼 트랜잭션을 둘로 쪼개지 않는다 — 이쪽은 느린 외부 I/O가
 * 없다({@code RunPositionReader} 는 로컬 Redis 단건 읽기뿐이고, 노선 계산처럼 외부 지도 API 를
 * 부르지 않는다). 선점({@link RunStopRepository#claimProximityNotice}) 뒤 이벤트 발행이 실패하면
 * 이 트랜잭션 전체가 롤백돼 선점도 함께 취소된다 — 그 근거는 그 메서드의 주석에 있다.
 */
@Component
@RequiredArgsConstructor
public class ProximityNotificationService {

    private static final int NEXT_STOP_LIMIT = 1;

    private final RunPositionReader runPositionReader;

    private final ProximityJudge proximityJudge;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final ApplicationEventPublisher eventPublisher;

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
     */
    @Transactional
    public void judgeOne(Long runId, Long academyId) {
        Optional<RunPositionSnapshot> position = runPositionReader.read(runId);
        if (position.isEmpty()) {
            return;
        }

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

        GeoPoint busPosition = new GeoPoint(position.get().lat(), position.get().lng());
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
}
