package src.backend.global.websocket;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.observability.metrics.WebSocketOutboundDropMetrics;
import src.backend.observability.metrics.WebSocketPublishMetrics;

/**
 * WebSocket 방송 송신을 한 곳으로 모은다(목표 4·5·6·10) — 리스너마다 {@code SimpMessagingTemplate}
 * 을 직접 들고 목적지 문자열을 조립하면, 채널 4종 중 하나를 빠뜨려도 컴파일도 테스트도 걸리지 않는
 * 조용한 누락이 된다. {@code run_started}·{@code run_ended}·{@code stop_arrived} 는 감사 대상(§7)이
 * 완전히 같은 4채널(회차 배치 학생 전원의 개인 채널 + 매니저 + 학원 + 관리자)이라 그 팬아웃을
 * {@link #broadcastToRunChannels} 하나로 묶는다 — 리스너 3개가 각자 다시 구현하면 그중 하나만 고쳐
 * 채널이 갈리는 사고가 난다.
 *
 * <p>발행 지연 계측이 이 게이트에 붙는 이유도 같다 — 리스너 8곳이 이미 넘기는 {@code occurredAt}
 * (원본 도메인 이벤트 시각)을 여기서 한 번만 현재 시각과 비교하면, 리스너가 늘어도 계측이 따라온다.
 *
 * <p><b>과부하 때 이벤트 종류별로 버림 여부가 갈리는 자리도 여기다</b>(BR-170, Ruling 349) —
 * 리스너를 고치지 않고 이 송신 채널에서 가른다. 위치({@code position})는 2~5초 뒤 새 값이
 * 덮어쓰는 데이터라 팬아웃 실행기 큐가 가득 차면 버리고, 나머지(비상·승하차 등)는 큐가 가득
 * 차도 {@code CallerRunsPolicy}(호출 스레드가 대신 보냄, {@link src.backend.global.config.WebSocketConfig})
 * 로 유실 없이 전달된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketBroadcastGateway {

    /** 과부하 때 버려도 되는 유일한 이벤트 종류(Ruling 349). */
    private static final String DROPPABLE_EVENT = "position";

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketPublishMetrics publishMetrics;
    private final WebSocketOutboundDropMetrics dropMetrics;
    @Qualifier("outboundTaskExecutor")
    private final ThreadPoolTaskExecutor outboundTaskExecutor;
    private final Clock clock;

    /** 목적지 1곳에 봉투를 실어 보내고 발행 지연을 계측한다. 큐 포화 때 {@code position} 은 버린다. */
    public void send(String destination, String event, Long runId, OffsetDateTime occurredAt, Object payload) {
        if (DROPPABLE_EVENT.equals(event) && isOutboundQueueFull()) {
            dropMetrics.recordPositionDropped();
            log.warn("팬아웃 실행기 큐 포화로 위치 방송 버림 — destination={}, runId={}", destination, runId);
            return;
        }
        messagingTemplate.convertAndSend(destination, new WebSocketEnvelope(event, runId, occurredAt, payload));
        publishMetrics.recordLatency(Duration.between(occurredAt, OffsetDateTime.now(clock)));
    }

    /** 팬아웃 실행기 큐에 남은 자리가 없는지 본다(BR-170) — 큐 상한은 {@code WebSocketConfig} 가 정한다. */
    private boolean isOutboundQueueFull() {
        ThreadPoolExecutor delegate = outboundTaskExecutor.getThreadPoolExecutor();
        return delegate.getQueue().remainingCapacity() == 0;
    }

    /**
     * {@code run_started}·{@code run_ended}·{@code stop_arrived} 공통 팬아웃(API_SPEC §7 채널 표) —
     * 학생 채널은 그 회차 명단에 오른 학생마다 각자의 {@code /topic/students/{id}/run} 으로 따로
     * 보낸다(같은 페이로드를 studentId 수만큼 반복 전송) — STOMP 심플 브로커에 학생별 목적지를 한 번에
     * 묶어 보내는 수단이 없어서다.
     *
     * <p>§20.2 파라미터 신호 — 6개를 파라미터 객체로 묶지 않는다. 리스너 9곳이 이미 각자의 이벤트
     * 레코드에서 값을 꺼내 이 순서 그대로 넘기는 관례라, 객체 하나로 싸면 그 변환 보일러플레이트가
     * 호출부마다 새로 생긴다.
     */
    public void broadcastToRunChannels(Long runId, Long academyId, List<Long> studentIds, String event,
            OffsetDateTime occurredAt, Object payload) {
        broadcastToRunChannels(runId, academyId, studentIds, event, occurredAt, payload, payload);
    }

    /**
     * 학생 채널에만 다른 payload 를 싣는 형태 — 학부모·학생 앱에 인원수를 보내지 않기 위함이다(C-08,
     * Ruling 335). 매니저·관제·관리자 채널은 {@code payload} 를 그대로 받는다.
     */
    public void broadcastToRunChannels(Long runId, Long academyId, List<Long> studentIds, String event,
            OffsetDateTime occurredAt, Object studentPayload, Object payload) {
        for (Long studentId : studentIds) {
            send(WebSocketDestinations.studentRun(studentId), event, runId, occurredAt, studentPayload);
        }
        send(WebSocketDestinations.managerRun(runId), event, runId, occurredAt, payload);
        send(WebSocketDestinations.academyLive(academyId), event, runId, occurredAt, payload);
        send(WebSocketDestinations.ADMIN_LIVE, event, runId, occurredAt, payload);
    }
}
