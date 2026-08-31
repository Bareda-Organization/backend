package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.event.StopArrivedEvent;

/**
 * {@code stop_arrived} 방송(목표 4·10) — {@code nextStopId} 가 최종 지점 도착 시 {@code null} 인
 * 경우(§4.5 자바독 근거)까지 payload 에 그대로 실리는지를 함께 본다.
 */
class StopArrivedBroadcastListenerTest {

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final StopArrivedBroadcastListener listener = new StopArrivedBroadcastListener(runRiderRepository, gateway);

    @Test
    @DisplayName("정차지 정보를 payload 에 그대로 싣고, 명단으로 팬아웃한다")
    void 명단_전원의_studentId_로_팬아웃한다() {
        Long runId = 10L;
        Long academyId = 1L;
        OffsetDateTime arrivedAt = OffsetDateTime.now();
        StopArrivedEvent event = new StopArrivedEvent(runId, academyId, 7L, 2, "정문 앞", arrivedAt, 8L);

        RunRider riderA = RunRider.uponConfirmation(runId, 100L, 7L);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(riderA));

        listener.broadcast(event);

        verify(gateway).broadcastToRunChannels(eq(runId), eq(academyId), argThat(studentIds -> {
            assertThat(studentIds).containsExactly(100L);
            return true;
        }), eq("stop_arrived"), eq(arrivedAt), argThat(payload -> {
            String text = String.valueOf(payload);
            assertThat(text).contains("stopId=7").contains("seq=2").contains("정문 앞").contains("nextStopId=8");
            return true;
        }));
    }

    @Test
    @DisplayName("최종 지점 도착이면 nextStopId 가 null 로 그대로 실린다")
    void 최종_지점이면_nextStopId_가_null_이다() {
        Long runId = 10L;
        Long academyId = 1L;
        OffsetDateTime arrivedAt = OffsetDateTime.now();
        StopArrivedEvent event = new StopArrivedEvent(runId, academyId, 9L, 5, "종점", arrivedAt, null);

        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of());

        listener.broadcast(event);

        verify(gateway).broadcastToRunChannels(eq(runId), eq(academyId), eq(List.of()), eq("stop_arrived"),
                eq(arrivedAt), argThat(payload -> {
                    assertThat(String.valueOf(payload)).contains("nextStopId=null");
                    return true;
                }));
    }
}
