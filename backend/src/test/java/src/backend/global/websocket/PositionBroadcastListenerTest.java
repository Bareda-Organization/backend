package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * {@code position} 방송(목표 4·6, C-08)의 채널별 payload 분기를 고정한다 — 학부모·학생 채널은
 * {@code eta} 키 자체가 부재해야 하고, 관제 채널(academy·admin)만 그 키를 가져야 한다. 두 단언을
 * 한 시험 안에 둔 이유는 {@code phase-goal-loop.md §5} 의 짝 규칙과 같다 — 부재만 보면 방송 자체가
 * 죽어도 통과하고, 존재만 보면 채널 구분이 사라져도 통과한다.
 */
class PositionBroadcastListenerTest {

    private final RunRepository runRepository = mock(RunRepository.class);
    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final ConfirmedRouteRepository confirmedRouteRepository = mock(ConfirmedRouteRepository.class);
    private final RunStopRepository runStopRepository = mock(RunStopRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final WaypointRepository waypointRepository = mock(WaypointRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final PositionBroadcastListener listener = new PositionBroadcastListener(runRepository,
            runRiderRepository, confirmedRouteRepository, runStopRepository, stopRepository, waypointRepository,
            gateway);

    @Test
    @DisplayName("목표 6 — 학부모·학생 채널은 eta 키가 없고, 관제 채널(academy·admin)만 eta 를 갖는다. 매니저 채널은 아예 받지 않는다")
    void 채널별_payload_가_다르다() {
        Long runId = 10L;
        Long academyId = 1L;
        Long studentId = 100L;
        BigDecimal lat = new BigDecimal("37.501234");
        BigDecimal lng = new BigDecimal("127.039876");
        OffsetDateTime recordedAt = OffsetDateTime.now();
        OffsetDateTime receivedAt = recordedAt.plusSeconds(1);
        RunPositionReceivedEvent event = new RunPositionReceivedEvent(runId, lat, lng, recordedAt, receivedAt);

        Run run = mock(Run.class);
        when(run.getId()).thenReturn(runId);
        when(run.getAcademyId()).thenReturn(academyId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunRider rider = RunRider.uponConfirmation(runId, studentId, 7L);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(rider));

        ConfirmedRoute confirmedRoute = mock(ConfirmedRoute.class);
        when(confirmedRoute.getCurrentVersionId()).thenReturn(50L);
        when(confirmedRouteRepository.findById(runId)).thenReturn(Optional.of(confirmedRoute));

        RunStop arrivedStop = mock(RunStop.class);
        when(arrivedStop.getArrivedAt()).thenReturn(recordedAt.minusMinutes(1));
        when(arrivedStop.getSeq()).thenReturn(2);
        when(arrivedStop.getStopId()).thenReturn(7L);

        OffsetDateTime nextEta = recordedAt.plusMinutes(15);
        RunStop nextStop = mock(RunStop.class);
        when(nextStop.getArrivedAt()).thenReturn(null);
        when(nextStop.getSeq()).thenReturn(3);
        when(nextStop.getEta()).thenReturn(nextEta);

        when(runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(50L, academyId))
                .thenReturn(List.of(arrivedStop, nextStop));

        Stop stop = mock(Stop.class);
        when(stop.getName()).thenReturn("정문 앞");
        when(stopRepository.findById(7L)).thenReturn(Optional.of(stop));

        listener.broadcast(event);

        // 매니저 채널은 §7 채널 표에 position 이 없다 — 절대 호출되지 않아야 한다.
        verify(gateway, never()).send(eq(WebSocketDestinations.managerRun(runId)), any(), any(), any(), any());
        verify(gateway, never()).broadcastToRunChannels(anyLong(), anyLong(), any(), any(), any(), any());

        // 학부모·학생 채널 — eta 키 자체가 없어야 한다.
        verify(gateway).send(eq(WebSocketDestinations.studentRun(studentId)), eq("position"), eq(runId),
                eq(receivedAt), org.mockito.ArgumentMatchers.argThat(payload -> {
                    String text = String.valueOf(payload);
                    assertThat(text).contains("lat=37.501234").contains("lng=127.039876")
                            .contains("receivedAt=" + receivedAt).contains("currentStopName=정문 앞")
                            .doesNotContain("eta");
                    return true;
                }));

        // 관제 채널(academy·admin) — 같은 좌표·정차지에 다음 미도착 정차 항목의 eta 값이 그대로 실린다
        // (Ruling 232 잠정). "eta=" 존재만 보면 "eta=null" 도 통과해 버려 값 자체는 검증하지 못한다 —
        // 그래서 정확한 값(nextEta)을 요구한다.
        verify(gateway).send(eq(WebSocketDestinations.academyLive(academyId)), eq("position"), eq(runId),
                eq(receivedAt), org.mockito.ArgumentMatchers.argThat(payload -> {
                    String text = String.valueOf(payload);
                    assertThat(text).contains("currentStopName=정문 앞").contains("eta=" + nextEta);
                    return true;
                }));
        verify(gateway).send(eq(WebSocketDestinations.ADMIN_LIVE), eq("position"), eq(runId), eq(receivedAt),
                org.mockito.ArgumentMatchers.argThat(payload -> {
                    String text = String.valueOf(payload);
                    assertThat(text).contains("eta=" + nextEta);
                    return true;
                }));
    }

    @Test
    @DisplayName("회차가 이미 사라졌으면 방송을 건너뛴다")
    void 회차가_없으면_건너뛴다() {
        Long runId = 999L;
        RunPositionReceivedEvent event = new RunPositionReceivedEvent(runId, BigDecimal.ONE, BigDecimal.ONE,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        listener.broadcast(event);

        verify(gateway, never()).send(any(), any(), any(), any(), any());
    }
}
