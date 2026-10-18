package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.location.infrastructure.RunBroadcastCache;
import src.backend.global.location.RunRiderDirectory;
import src.backend.global.websocket.BroadcastDelivery;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.location.event.RunPositionReceivedEvent;

/**
 * {@code position} 방송(C-08)의 채널별 payload 분기를 고정한다 — 학부모·학생 채널은
 * {@code eta} 키 자체가 부재해야 하고, 관제 채널(academy·admin)만 그 키를 가져야 한다. 두 단언을
 * 한 시험 안에 둔 이유는 짝 검사 규칙과 같다 — 부재만 보면 방송 자체가
 * 죽어도 통과하고, 존재만 보면 채널 구분이 사라져도 통과한다.
 *
 * <p>현재 정차지 이름·다음 ETA 는 이벤트에 실려 온다(BR-100) — 그 값을 고르는 규칙은
 * {@code RunPositionCommandServiceTest} 가 본다. 여기서는 받은 값을 채널별로 제자리에 싣는지만 본다.
 */
class PositionBroadcastListenerTest {

    private final RunRiderDirectory runRiderDirectory = mock(RunRiderDirectory.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final PositionBroadcastListener listener = new PositionBroadcastListener(runRiderDirectory,
            new RunBroadcastCache(java.time.Clock.systemUTC()), gateway);

    @Test
    @DisplayName("학부모·학생 채널은 eta 키가 없고, 관제 채널(academy·admin)만 eta 를 갖는다. 매니저 채널은 아예 받지 않는다")
    void 채널별_payload_가_다르다() {
        Long runId = 10L;
        Long academyId = 1L;
        Long studentId = 100L;
        BigDecimal lat = new BigDecimal("37.501234");
        BigDecimal lng = new BigDecimal("127.039876");
        OffsetDateTime recordedAt = OffsetDateTime.now();
        OffsetDateTime receivedAt = recordedAt.plusSeconds(1);
        OffsetDateTime nextEta = recordedAt.plusMinutes(15);
        RunPositionReceivedEvent event = new RunPositionReceivedEvent(runId, lat, lng, recordedAt, receivedAt,
                academyId, "정문 앞", nextEta);

        // 수신자는 absent(다른 버스로 옮긴 removed 포함)를 뺀 학생 id 만 — 학생 채널이 학생 단위라 여기 보내면 학부모 지도에 버스 두 대가
        // 번갈아 뜬다. 그 거름은 조회에 absent 제외를 넘기는 것으로 하므로, 그 인자로 부른 경우에만 학생 id 를 돌려준다.
        given(runRiderDirectory.studentIdsExcludingAbsent(runId))
                .willReturn(List.of(studentId));

        listener.broadcast(event);

        // 이벤트 1건 = 방송 1건 — 학생 채널 묶음(학부모·학생 payload)과 관제 채널 묶음(eta 포함 payload)이 한 번에 나간다.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<BroadcastDelivery>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gateway).broadcast(eq("position"), eq(runId), eq(receivedAt), captor.capture());
        verify(gateway, never()).send(any(), any(), any(), any(), any());
        verify(gateway, never()).broadcastToRunChannels(anyLong(), anyLong(), any(), any(), any(), any());
        List<BroadcastDelivery> deliveries = captor.getValue();
        assertThat(deliveries).hasSize(2);

        // 학부모·학생 채널 — eta 키 자체가 없어야 한다. 받지 않는 학생(200)·매니저 채널은 어느 묶음에도 없다.
        BroadcastDelivery student = deliveries.get(0);
        assertThat(student.destinations()).containsExactly(WebSocketDestinations.studentRun(studentId));
        String studentText = String.valueOf(student.payload());
        assertThat(studentText).contains("lat=37.501234").contains("lng=127.039876")
                .contains("receivedAt=" + receivedAt).contains("currentStopName=정문 앞").doesNotContain("eta");

        // 관제 채널(academy·admin) — 같은 좌표·정차지에 다음 정차 항목의 eta 값이 그대로 실린다
        // (Ruling 232 확정). "eta=" 존재만 보면 "eta=null" 도 통과해 버려 값 자체를 요구한다.
        BroadcastDelivery control = deliveries.get(1);
        assertThat(control.destinations()).containsExactly(WebSocketDestinations.academyLive(academyId),
                WebSocketDestinations.ADMIN_LIVE);
        assertThat(String.valueOf(control.payload())).contains("currentStopName=정문 앞").contains("eta=" + nextEta);
        // 매니저 채널은 §7 채널 표에 position 이 없다 — 어느 묶음에도 들어가면 안 된다.
        assertThat(deliveries).flatExtracting(BroadcastDelivery::destinations)
                .doesNotContain(WebSocketDestinations.managerRun(runId), WebSocketDestinations.studentRun(200L));
    }
}
