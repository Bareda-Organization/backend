package src.backend.global.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
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
 * {@code position} 방송(API_SPEC §7·§7.1, 목표 4·6) — 채널 3종뿐이다. {@code /ws/manager/runs/{id}}
 * 는 §7 채널 표에 {@code position} 이 없어 제외한다(매니저 채널은 {@code rider_changed}·{@code
 * stop_arrived}·{@code run_started}·{@code run_ended}·{@code emergency_acked} 만 받는다).
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 근거는 {@link RunStartedBroadcastListener} 와 같다.
 *
 * <p><b>학부모·학생 채널과 관제 채널(academy·admin)은 페이로드 타입 자체가 다르다</b>(C-08) —
 * {@code eta} 는 값을 {@code null} 로 비우는 것이 아니라 <b>레코드 컴포넌트 자체를 두지 않아야</b>
 * 학부모·학생 쪽 JSON 에서 키가 사라진다({@link ParentStudentPayload}). 관제 쪽만 그 컴포넌트를 가진
 * {@link ControlPayload} 를 쓴다.
 *
 * <p>⚠ <b>{@code eta} 계산 자체는 이 Phase 의 범위 밖이다</b> — {@code src.backend.run.query
 * .RunQueryService} 자바독이 명시하듯 관제용 실시간 스냅샷(§5.18 {@code GET /staff/runs/live},
 * MON-07)은 Phase 13 이고, §6.8 관제 live 조회에도 이 값을 만드는 서비스가 아직 없다(조율자·구현자
 * 공통 실측). 계산 수단이 생기기 전까지 관제 채널의 {@code eta} 는 항상 {@code null} 이다 — 키는
 * 사양대로 존재하되 값을 아직 못 채우는 상태이고, Phase 13 이 이 필드를 채우면 된다.
 *
 * <p>{@code current_stop_name} 은 이벤트에 실려 오지 않는다({@link RunPositionReceivedEvent} 는
 * 좌표·시각만 담는 공유 계약이라 임의로 필드를 늘리지 않는다) — {@link src.backend.location.command
 * .RunPositionRedisListener} 가 Redis 갱신에 쓰는 것과 <b>같은 판정을 이 리스너가 다시 계산</b>한다.
 * Redis 캐시를 읽지 않는 이유는 그 리스너도 같은 {@code AFTER_COMMIT} 단계에서 동시에 도는 별개의
 * 리스너라 실행 순서를 보장할 수 없어서다 — 내가 먼저 돌면 아직 갱신 전인 이전 값을 읽는다. DB 에서
 * 매번 다시 계산하면 그 경쟁이 사라진다.
 */
@Component
@RequiredArgsConstructor
public class PositionBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(PositionBroadcastListener.class);

    private static final String EVENT = "position";

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RunPositionReceivedEvent event) {
        Run run = runRepository.findById(event.runId()).orElse(null);
        if (run == null) {
            log.warn("[websocket] position 방송 대상 회차가 부재해 건너뛴다. runId={}", event.runId());
            return;
        }

        String currentStopName = currentStopNameOf(run);
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                .map(RunRider::getStudentId)
                .distinct()
                .toList();

        ParentStudentPayload parentStudentPayload = new ParentStudentPayload(event.lat(), event.lng(),
                event.receivedAt(), currentStopName);
        for (Long studentId : studentIds) {
            gateway.send(WebSocketDestinations.studentRun(studentId), EVENT, event.runId(), event.receivedAt(),
                    parentStudentPayload);
        }

        ControlPayload controlPayload = new ControlPayload(event.lat(), event.lng(), event.receivedAt(),
                currentStopName, null);
        gateway.send(WebSocketDestinations.academyLive(run.getAcademyId()), EVENT, event.runId(), event.receivedAt(),
                controlPayload);
        gateway.send(WebSocketDestinations.ADMIN_LIVE, EVENT, event.runId(), event.receivedAt(), controlPayload);
    }

    /**
     * 가장 최근 도착 처리된 정차 항목의 이름 — {@link src.backend.location.command.RunPositionRedisListener
     * #currentStopNameOf} 와 같은 판정("도착 시각이 채워진 정차 중 seq 최댓값")을 이 리스너 자신의
     * 소유 범위({@code global/websocket}) 안에서 다시 계산한다. 그 클래스를 직접 재사용하지 않는 이유도
     * 같다 — 위 자바독의 실행 순서 문제 때문에 각자 독립적으로 계산해야 한다.
     */
    private String currentStopNameOf(Run run) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId()).orElse(null);
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

    /** {@code lat} · {@code lng} · {@code received_at} · {@code current_stop_name}. {@code eta} 키 자체가 없다(C-08). */
    private record ParentStudentPayload(BigDecimal lat, BigDecimal lng, OffsetDateTime receivedAt,
            String currentStopName) {
    }

    /** 위 4개에 {@code eta} 를 더한다 — 관제 채널(academy·admin) 전용. 계산 수단이 없어 지금은 항상 {@code null}. */
    private record ControlPayload(BigDecimal lat, BigDecimal lng, OffsetDateTime receivedAt, String currentStopName,
            OffsetDateTime eta) {
    }
}
