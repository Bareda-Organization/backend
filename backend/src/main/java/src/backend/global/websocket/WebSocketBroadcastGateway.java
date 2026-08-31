package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * WebSocket 방송 송신을 한 곳으로 모은다(목표 4·5·6·10) — 리스너마다 {@code SimpMessagingTemplate}
 * 을 직접 들고 목적지 문자열을 조립하면, 채널 4종 중 하나를 빠뜨려도 컴파일도 테스트도 걸리지 않는
 * 조용한 누락이 된다. {@code run_started}·{@code run_ended}·{@code stop_arrived} 는 감사 대상(§7)이
 * 완전히 같은 4채널(회차 배치 학생 전원의 개인 채널 + 매니저 + 학원 + 관리자)이라 그 팬아웃을
 * {@link #broadcastToRunChannels} 하나로 묶는다 — 리스너 3개가 각자 다시 구현하면 그중 하나만 고쳐
 * 채널이 갈리는 사고가 난다.
 */
@Component
@RequiredArgsConstructor
public class WebSocketBroadcastGateway {

    private final SimpMessagingTemplate messagingTemplate;

    public void send(String destination, String event, Long runId, OffsetDateTime occurredAt, Object payload) {
        messagingTemplate.convertAndSend(destination, new WebSocketEnvelope(event, runId, occurredAt, payload));
    }

    /**
     * {@code run_started}·{@code run_ended}·{@code stop_arrived} 공통 팬아웃(API_SPEC §7 채널 표) —
     * 학생 채널은 그 회차 명단에 오른 학생마다 각자의 {@code /topic/students/{id}/run} 으로 따로
     * 보낸다(같은 페이로드를 studentId 수만큼 반복 전송) — STOMP 심플 브로커에 학생별 목적지를 한 번에
     * 묶어 보내는 수단이 없어서다.
     */
    public void broadcastToRunChannels(Long runId, Long academyId, List<Long> studentIds, String event,
            OffsetDateTime occurredAt, Object payload) {
        for (Long studentId : studentIds) {
            send(WebSocketDestinations.studentRun(studentId), event, runId, occurredAt, payload);
        }
        send(WebSocketDestinations.managerRun(runId), event, runId, occurredAt, payload);
        send(WebSocketDestinations.academyLive(academyId), event, runId, occurredAt, payload);
        send(WebSocketDestinations.ADMIN_LIVE, event, runId, occurredAt, payload);
    }
}
