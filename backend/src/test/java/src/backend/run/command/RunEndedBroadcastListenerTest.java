package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.observability.metrics.WebSocketPublishMetrics;
import src.backend.run.event.RunEndedEvent;

/**
 * {@code run_ended} 방송(목표 4·10) — {@code autoAlightedCount} 는 T3 가 이벤트 필드를 비워 둔 채
 * 넘겼으므로(생성 시점 근거는 {@link RunEndedEvent} 자바독), 이 리스너가 직접 채우는지가 검증 핵심이다.
 */
class RunEndedBroadcastListenerTest {

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final WebSocketBroadcastGateway gateway = new WebSocketBroadcastGateway(template,
            mock(WebSocketPublishMetrics.class), Clock.systemUTC());

    private final RunEndedBroadcastListener listener = new RunEndedBroadcastListener(runRiderRepository, gateway);

    /** Ruling 335 — 학생 채널에는 {@code auto_alighted_count} 를 싣지 않는다(C-08). 나머지 3채널은 싣는다. */
    @Test
    @DisplayName("학생 채널은 인원수 없이, 나머지 3채널은 인원수와 함께 보낸다")
    void 학생_채널은_인원수를_싣지_않는다() {
        Long runId = 10L;
        Long academyId = 1L;
        OffsetDateTime finishedAt = OffsetDateTime.now();
        RunEndedEvent event = new RunEndedEvent(runId, academyId, finishedAt, 5L);

        RunRider riderA = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider riderB = RunRider.uponConfirmation(runId, 200L, 2L);
        given(runRiderRepository.findAllByRunId(runId)).willReturn(List.of(riderA, riderB));

        listener.broadcast(event);

        Map<String, String> sent = RunStartedBroadcastListenerTest.sentPayloads(template);
        assertThat(sent).containsOnlyKeys("/topic/students/100/run", "/topic/students/200/run",
                "/topic/manager/runs/10", "/topic/academy/1/live", "/topic/admin/live");
        assertThat(sent.get("/topic/students/200/run")).contains("runStatus=finished").doesNotContain("autoAlightedCount");
        assertThat(sent.get("/topic/manager/runs/10")).contains("autoAlightedCount=5");
        assertThat(sent.get("/topic/academy/1/live")).contains("autoAlightedCount=5");
        assertThat(sent.get("/topic/admin/live")).contains("autoAlightedCount=5");
    }

    @Test
    @DisplayName("absent 행(다른 버스로 옮긴 removed 포함)의 학생 채널에는 보내지 않는다")
    void absent_학생_채널에는_보내지_않는다() {
        Long runId = 10L;
        OffsetDateTime finishedAt = OffsetDateTime.now();
        RunRider rider = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider moved = RunRider.uponConfirmation(runId, 200L, 2L);
        moved.markRemoved(finishedAt);
        given(runRiderRepository.findAllByRunId(runId)).willReturn(List.of(rider, moved));

        listener.broadcast(new RunEndedEvent(runId, 1L, finishedAt, 0L));

        // 실제 게이트웨이로 보내 목적지를 센다(Ruling 335 시험과 같은 방식) — 옮긴 학생(200)의 채널은 부재.
        Map<String, String> sent = RunStartedBroadcastListenerTest.sentPayloads(template);
        assertThat(sent).containsKey("/topic/students/100/run").doesNotContainKey("/topic/students/200/run");
    }
}
