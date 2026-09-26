package src.backend.monitoring.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ChangeType;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.routing.entity.RunStop;
import src.backend.routing.query.CurrentRunStopResolver;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * 회차 1건의 "지금" 상태 판정 — §5.18({@code GET /staff/runs/live}, T1)과 §6.8(T2)이 공유하는 세 판정을
 * 한 곳에 둔다(Phase 13 §2): ①최신 좌표의 유실 판정 ②현재 정차(도착 처리된 정차 중
 * {@code seq} 최댓값) ③다음 정차(미도착·비건너뜀 정차 중 {@code seq} 최솟값).
 *
 * <p>좌표는 호출부가 {@code RunPositionStore#findAll} 로 회차 전부를 한 번에 읽어 넘긴다(BR-166·BR-167) — 여기서
 * 회차마다 읽으면 Redis 가 답하지 않을 때 기다림이, Redis 가 죽었을 때 대체 조회가 회차 수만큼 곱해진다.
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

    private final Clock clock;

    /**
     * 호출부가 이미 읽은 정차 순서({@code seq} 오름차순)와 최신 좌표({@code null} = 아직 없음)로 판정한다(BR-065) —
     * 관제 화면은 응답을 그리려고 정차 목록을 먼저 읽으므로, 여기서 확정 노선·정차를 다시 읽으면 회차마다 같은 쿼리가
     * 두 번 나간다. 두 재료는 호출부가 이미 학원 범위로 확인한 회차의 것이라는 전제다.
     */
    public RunLiveState resolve(List<RunStop> orderedStops, RunPositionRedisValue position) {
        boolean stale = isStale(position);
        StopPair stops = currentNextStopIdsOf(orderedStops);
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
    private boolean isStale(RunPositionRedisValue position) {
        if (position == null || position.receivedAt() == null) {
            return true;
        }
        Duration elapsed = Duration.between(position.receivedAt(), OffsetDateTime.now(clock));
        return elapsed.compareTo(StudentBusPositionQueryService.STALE_THRESHOLD) >= 0;
    }

    /** 정차가 없으면(확정 노선 부재) 둘 다 null 이다. */
    private StopPair currentNextStopIdsOf(List<RunStop> stops) {
        RunStop current = CurrentRunStopResolver.resolve(stops).orElse(null);
        int afterSeq = current == null ? -1 : current.getSeq();
        // 마지막 도착 뒤에서만 고른다(BR-015) — 도착 처리 대상이 아닌 경유 지점이 미도착으로 남아 있어도
        // 그 뒤 승하차지에 도착했으면 지난 것이다(§4.3 RunRouteQueryService 와 같은 규칙).
        Long nextStopId = stops.stream()
                .filter(stop -> stop.getSeq() > afterSeq && stop.getChange() != ChangeType.SKIPPED)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getId)
                .orElse(null);
        Long currentStopId = current == null ? null : current.getId();
        return new StopPair(currentStopId, nextStopId);
    }

    private record StopPair(Long currentStopId, Long nextStopId) {
    }
}
