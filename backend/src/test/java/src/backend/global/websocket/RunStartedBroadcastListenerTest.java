package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.observability.metrics.WebSocketPublishMetrics;
import src.backend.run.event.RunStartedEvent;

/**
 * {@code run_started} 방송(목표 4·10) — 게이트웨이는 진짜를 쓰고 송신 템플릿만 가짜로 둬, 채널마다 <b>실제로
 * 나간 payload</b> 를 본다. 학생 채널과 나머지 채널의 payload 가 갈리므로(Ruling 335) 게이트웨이 호출 인자만
 * 봐서는 학생 채널에 무엇이 나갔는지 알 수 없다.
 */
class RunStartedBroadcastListenerTest {

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final WebSocketBroadcastGateway gateway = new WebSocketBroadcastGateway(template,
            mock(WebSocketPublishMetrics.class), Clock.systemUTC());

    private final RunStartedBroadcastListener listener = new RunStartedBroadcastListener(runRiderRepository, gateway);

    /**
     * Ruling 335 — 학생 채널(학부모·학생 앱)에는 인원수를 싣지 않는다(C-08 "탑승 인원 미표시"). 매니저·관제·
     * 관리자 채널은 그대로 싣는다. 명단의 studentId 는 중복 없이 한 번씩만 보낸다.
     */
    @Test
    @DisplayName("학생 채널은 인원수 없이, 나머지 3채널은 인원수와 함께 보낸다")
    void 학생_채널은_인원수를_싣지_않는다() {
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

        Map<String, String> sent = sentPayloads(template);
        assertThat(sent).containsOnlyKeys("/topic/students/100/run", "/topic/students/200/run",
                "/topic/manager/runs/10", "/topic/academy/1/live", "/topic/admin/live");
        assertThat(sent.get("/topic/students/100/run")).contains("runStatus=moving").doesNotContain("autoBoardedCount");
        assertThat(sent.get("/topic/manager/runs/10")).contains("autoBoardedCount=3");
        assertThat(sent.get("/topic/academy/1/live")).contains("autoBoardedCount=3");
        assertThat(sent.get("/topic/admin/live")).contains("autoBoardedCount=3");
    }

    /** 목적지별로 실제 송신된 봉투의 payload 를 문자열로 모은다 — 게이트웨이는 진짜, 송신 템플릿만 가짜다. */
    static Map<String, String> sentPayloads(SimpMessagingTemplate template) {
        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> envelopes = ArgumentCaptor.forClass(Object.class);
        verify(template, atLeastOnce()).convertAndSend(destinations.capture(), envelopes.capture());
        Map<String, String> byDestination = new HashMap<>();
        for (int i = 0; i < destinations.getAllValues().size(); i++) {
            byDestination.put(destinations.getAllValues().get(i),
                    String.valueOf(((WebSocketEnvelope) envelopes.getAllValues().get(i)).payload()));
        }
        return byDestination;
    }

    @Test
    @DisplayName("absent 행(다른 버스로 옮긴 removed 포함)의 학생 채널에는 보내지 않는다 — 그 학생은 이 버스에 없다")
    void absent_학생_채널에는_보내지_않는다() {
        Long runId = 10L;
        OffsetDateTime startedAt = OffsetDateTime.now();
        RunRider rider = RunRider.uponConfirmation(runId, 100L, 1L);
        RunRider moved = RunRider.uponConfirmation(runId, 200L, 2L);
        moved.markRemoved(startedAt);
        when(runRiderRepository.findAllByRunId(runId)).thenReturn(List.of(rider, moved));

        listener.broadcast(new RunStartedEvent(runId, 1L, startedAt, 0));

        // 실제 게이트웨이로 보내 목적지를 센다(Ruling 335 시험과 같은 방식) — 옮긴 학생(200)의 채널은 부재.
        Map<String, String> sent = sentPayloads(template);
        assertThat(sent).containsKey("/topic/students/100/run").doesNotContainKey("/topic/students/200/run");
    }
}
