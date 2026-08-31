package src.backend.location.command;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * {@link RunPositionReceivedEvent} 커밋 후 Redis 최신 좌표를 갱신한다(목표 3, 조율자 판단) — 이력
 * ({@code run_position})이 먼저 적재된 뒤에만 도는 이유는
 * {@link RunPositionCommandService} 자바독을 본다.
 *
 * <p>예외를 삼키는 이유는 {@link src.backend.notification.command.NotificationDispatchListener} 와
 * 같다 — 이미 커밋된 위치 수신을 여기서 실패로 뒤집을 수 없고, 이 갱신이 실패해도 5~10초 뒤 다음
 * 송신이 같은 키를 덮어써 스스로 회복된다(조율자 판단, 목표 3).
 */
@Component
@RequiredArgsConstructor
public class RunPositionRedisListener {

    private static final Logger log = LoggerFactory.getLogger(RunPositionRedisListener.class);

    /** 운행 종료 후 자연 소멸 — 이력은 {@code run_position} 이 별도로 갖는다(공유 계약, 목표 3). */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisAfterCommit(RunPositionReceivedEvent event) {
        try {
            String currentStopName = currentStopNameOf(event.runId());
            RunPositionRedisValue value = new RunPositionRedisValue(event.lat(), event.lng(), event.recordedAt(),
                    event.receivedAt(), currentStopName);
            redisTemplate.opsForValue().set(keyOf(event.runId()), value, TTL);
        } catch (RuntimeException e) {
            log.warn("[location] Redis 최신 좌표 갱신이 실패해 다음 송신으로 넘긴다. runId={}", event.runId(), e);
        }
    }

    private String keyOf(Long runId) {
        return "run:" + runId + ":position";
    }

    /**
     * 가장 최근 도착 처리된 정차 항목의 이름 — {@link src.backend.run.query.RunRouteQueryService} 와
     * 같은 판정("도착 시각이 채워진 정차 중 seq 최댓값")을 쓴다. 그 클래스를 직접 재사용하지 않고
     * 다시 적은 이유는 이 좌석의 소유 범위({@code location/}) 밖 클래스를 의존으로 들이지 않기
     * 위해서다 — 읽기 전용 조회라 중복의 비용이 낮다.
     */
    private String currentStopNameOf(Long runId) {
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return null;
        }
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId).orElse(null);
        if (confirmedRoute == null || confirmedRoute.getCurrentVersionId() == null) {
            return null;
        }
        List<RunStop> ordered = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                confirmedRoute.getCurrentVersionId(), run.getAcademyId());
        RunStop currentRunStop = ordered.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq))
                .orElse(null);
        return currentRunStop == null ? null : nameOf(currentRunStop);
    }

    private String nameOf(RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(Stop::getName).orElse(null);
        }
        return waypointRepository.findById(stop.getWaypointId()).map(Waypoint::getLabel).orElse(null);
    }
}
