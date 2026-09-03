package src.backend.monitoring.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ChangeType;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * 회차 1건의 "지금" 상태 판정 — §5.18({@code GET /staff/runs/live}, T1)과 §6.8(T2)이 공유하는 세 판정을
 * 한 곳에 둔다(Phase 13 §2): ①Redis 최신 좌표 읽기 + 유실 판정 ②현재 정차(도착 처리된 정차 중
 * {@code seq} 최댓값) ③다음 정차(미도착·비건너뜀 정차 중 {@code seq} 최솟값).
 *
 * <p>{@code PositionBroadcastListener.currentStopNameOf} · {@code RunPositionRedisListener} 가 이미
 * ②와 비슷한 계산을 각자 하고 있지만, 그 두 클래스는 AFTER_COMMIT 리스너 간 실행 순서를 이유로
 * 자기 트랜잭션 맥락 안에서 계산한다 — 이 클래스가 대체하는 것은 그 리스너들이 아니라 REST 조회
 * 경로이며, 그 이유가 REST 조회에는 해당하지 않는다(같은 계산을 리스너와 별개로 다시 갖는 이유).
 *
 * <p><b>응답 DTO 가 아니다</b> — {@link RunLiveState} 참고. §5.18·§6.8 은 이 판정 결과를 각자의
 * 응답 형태로 옮겨 담는다.
 */
@Component
@RequiredArgsConstructor
public class RunLiveStateResolver {

    private final RunPositionReader runPositionReader;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final Clock clock;

    /** 회차 1건의 위치·유실·현재/다음 정차 판정. {@code run} 은 호출부가 이미 학원 범위로 확인한 것이라는 전제다. */
    public RunLiveState resolve(Run run) {
        RunPositionSnapshot position = runPositionReader.read(run.getId()).orElse(null);
        boolean stale = isStale(position);
        StopPair stops = currentNextStopIdsOf(run);
        return new RunLiveState(
                position == null ? null : position.lat(),
                position == null ? null : position.lng(),
                position == null ? null : position.recordedAt(),
                position == null ? null : position.receivedAt(),
                stale,
                stops.currentStopId(),
                stops.nextStopId());
    }

    /**
     * {@code StudentBusPositionQueryService.STALE_THRESHOLD} 를 그대로 참조한다 — 값을 복사하지 않는
     * 이유는 그 상수 자바독과 Phase 13 목표 7 을 본다.
     */
    private boolean isStale(RunPositionSnapshot position) {
        if (position == null || position.receivedAt() == null) {
            return true;
        }
        Duration elapsed = Duration.between(position.receivedAt(), OffsetDateTime.now(clock));
        return elapsed.compareTo(StudentBusPositionQueryService.STALE_THRESHOLD) >= 0;
    }

    /** 확정 노선이 없으면(아직 미확정) 둘 다 null 이다. */
    private StopPair currentNextStopIdsOf(Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return new StopPair(null, null);
        }
        List<RunStop> stops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                currentVersionId, run.getAcademyId());
        Long currentStopId = stops.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getId)
                .orElse(null);
        Long nextStopId = stops.stream()
                .filter(stop -> stop.getArrivedAt() == null && stop.getChange() != ChangeType.SKIPPED)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getId)
                .orElse(null);
        return new StopPair(currentStopId, nextStopId);
    }

    private record StopPair(Long currentStopId, Long nextStopId) {
    }
}
