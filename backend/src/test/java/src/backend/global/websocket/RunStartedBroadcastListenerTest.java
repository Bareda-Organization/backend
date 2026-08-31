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
import src.backend.run.event.RunStartedEvent;

/**
 * {@code run_started} 방송(목표 4·10)의 팬아웃 인자를 고정한다 — 실제 송신 채널 4종 조립은
 * {@link WebSocketBroadcastGateway#broadcastToRunChannels} 가 이미 책임지므로(공통 경로), 이 리스너가
 * 검증할 것은 <b>그 경로에 넘기는 studentIds·payload 가 옳은가</b> 뿐이다.
 */
class RunStartedBroadcastListenerTest {

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final RunStartedBroadcastListener listener = new RunStartedBroadcastListener(runRiderRepository, gateway);

    @Test
    @DisplayName("회차 명단의 studentId 를 중복 없이 뽑아 4채널 공통 팬아웃에 넘긴다")
    void 명단_전원의_studentId_로_팬아웃한다() {
        Long runId = 10L;
        Long academyId = 1L;
        OffsetDateTime startedAt = OffsetDateTime.now();
        RunStartedEvent event = new RunStartedEvent(runId, academyId, startedAt, 3);

        // 같은 studentId 가 두 정차지에 걸쳐 두 행으로 있어도(예: 경유 중 재승차 이력) distinct 여야 한다.
        RunRider riderA = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider riderB = RunRider.uponConfirmation(runId, 200L, 2L);
        RunRider riderADuplicate = RunRider.uponConfirmation(runId, 100L, 3L);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(riderA, riderB, riderADuplicate));

        listener.broadcast(event);

        verify(gateway).broadcastToRunChannels(eq(runId), eq(academyId), argThat(studentIds -> {
            assertThat(studentIds).containsExactlyInAnyOrder(100L, 200L);
            return true;
        }), eq("run_started"), eq(startedAt), argThat(payload -> {
            String text = String.valueOf(payload);
            assertThat(text).contains("runStatus=moving").contains("autoBoardedCount=3");
            return true;
        }));
    }
}
