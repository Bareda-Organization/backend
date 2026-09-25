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
import src.backend.boarding.event.RunEndedEvent;
import src.backend.boarding.repository.RunRiderRepository;

/**
 * {@code run_ended} 방송(목표 4·10) — {@code autoAlightedCount} 는 T3 가 이벤트 필드를 비워 둔 채
 * 넘겼으므로(생성 시점 근거는 {@link RunEndedEvent} 자바독), 이 리스너가 직접 채우는지가 검증 핵심이다.
 */
class RunEndedBroadcastListenerTest {

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final RunEndedBroadcastListener listener = new RunEndedBroadcastListener(runRiderRepository, gateway);

    @Test
    @DisplayName("이벤트에 실려 온 autoAlightedCount 를 그대로 payload 에 싣고, 명단으로 팬아웃한다")
    void 명단_전원의_studentId_로_팬아웃한다() {
        Long runId = 10L;
        Long academyId = 1L;
        OffsetDateTime finishedAt = OffsetDateTime.now();
        RunEndedEvent event = new RunEndedEvent(runId, academyId, finishedAt, 5L);

        RunRider riderA = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider riderB = RunRider.uponConfirmation(runId, 200L, 2L);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(riderA, riderB));

        listener.broadcast(event);

        verify(gateway).broadcastToRunChannels(eq(runId), eq(academyId), argThat(studentIds -> {
            assertThat(studentIds).containsExactlyInAnyOrder(100L, 200L);
            return true;
        }), eq("run_ended"), eq(finishedAt), argThat(payload -> {
            String text = String.valueOf(payload);
            assertThat(text).contains("runStatus=finished").contains("autoAlightedCount=5");
            return true;
        }));
    }

    @Test
    @DisplayName("absent 행(다른 버스로 옮긴 removed 포함)의 학생 채널에는 보내지 않는다")
    void absent_학생_채널에는_보내지_않는다() {
        Long runId = 10L;
        OffsetDateTime finishedAt = OffsetDateTime.now();
        RunRider rider = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider moved = RunRider.uponConfirmation(runId, 200L, 2L);
        moved.markRemoved(finishedAt);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(rider, moved));

        listener.broadcast(new RunEndedEvent(runId, 1L, finishedAt, 0L));

        verify(gateway).broadcastToRunChannels(eq(runId), eq(1L), argThat(studentIds -> {
            assertThat(studentIds).containsExactly(100L);
            return true;
        }), eq("run_ended"), eq(finishedAt), argThat(payload -> true));
    }
}
