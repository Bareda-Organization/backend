package src.backend.global.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.error.ErrorCode;

/**
 * STOMP 세션은 그것을 연 access 토큰보다 오래 살지 않는다(BR-083).
 *
 * <p>인증은 CONNECT 에서 한 번뿐이라, 퇴사·차단(refresh 무효화)이나 토큰 만료 뒤에도 열린 세션이 관제·자녀
 * 채널 방송을 계속 받았다. REST 는 access 토큰 만료(15분)로 권한이 회수되므로 세션도 같은 시각에 끊는다 —
 * 다시 붙으려면 새 토큰이 필요하고, 그 재발급이 퇴사·차단에서 막힌다.
 *
 * <p>출력 채널 인터셉터로 둔다 — 만료 뒤 첫 방송을 {@code TOKEN_EXPIRED} ERROR 프레임으로 바꿔 보내면
 * Spring 이 ERROR 뒤 세션을 닫는다({@code StompSubProtocolHandler#sendToClient}). 방송 메시지 헤더에는 세션
 * 속성이 실리지 않아 세션 id 로 만료 시각을 들고 있고, 연결이 끊기면 지운다.
 *
 * <p><b>권한을 잃은 계정의 세션도 같은 길로 끊는다</b>(Ruling 880). 퇴사·차단·삭제된 매니저의 계정이 열어 둔 세션은 토큰 만료를
 * 기다리지 않고, 30초마다 도는 스케줄러({@link StompStandingSweepScheduler})가 열린 세션의 계정을 묶어 점검해 해당 세션의 만료 시각을
 * 지금으로 당긴다. 방송 송신 스레드는 DB 를 묻지 않는다(방송은 초당 수만 건) — 그래서 이미 열린 세션이 받는 방송은 최대 이 간격만큼 남는다.
 * 새 구독·송신은 {@link StompAuthChannelInterceptor} 가 요청마다 바로 거절한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompSessionExpiry implements ChannelInterceptor {

    /** 세션을 연 계정과 그 세션이 살 수 있는 마지막 시각. */
    private record Tracked(Long accountId, Instant expiresAt) {
    }

    private final Map<String, Tracked> trackedBySession = new ConcurrentHashMap<>();

    private final Clock clock;

    private final AccountStanding accountStanding;

    private final MeterRegistry meterRegistry;

    /** CONNECT 인증이 끝난 세션의 계정과 토큰 만료 시각을 남긴다. */
    void record(String sessionId, Long accountId, Instant expiresAt) {
        trackedBySession.put(sessionId, new Tracked(accountId, expiresAt));
    }

    boolean isExpired(String sessionId) {
        Tracked tracked = sessionId == null ? null : trackedBySession.get(sessionId);
        return tracked != null && !Instant.now(clock).isBefore(tracked.expiresAt());
    }

    /** 이 세션을 지금 만료로 표시한다 — 다음 방송이 {@code TOKEN_EXPIRED} ERROR 로 바뀌어 연결이 닫힌다. */
    void expire(String sessionId) {
        if (sessionId != null) {
            trackedBySession.computeIfPresent(sessionId, (id, tracked) -> new Tracked(tracked.accountId(), Instant.now(clock)));
        }
    }

    /** 이 세션이 인증된 CONNECT 를 마쳤는지 — CONNECT 마감({@code ConnectDeadlineFactory})이 묻는다. */
    public boolean isConnected(String sessionId) {
        return sessionId != null && trackedBySession.containsKey(sessionId);
    }

    /** 주어진 계정이 연 세션의 id — 권한이 바뀐 계정의 세션을 닫을 때 쓴다(한 계정이 여러 기기로 여러 세션을 연다). */
    public Set<String> sessionIdsOf(Collection<Long> accountIds) {
        return trackedBySession.entrySet().stream()
                .filter(entry -> accountIds.contains(entry.getValue().accountId()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** 만료 시각을 들고 있는 세션 수 — 끊긴 세션이 이 맵에 남지 않는지 실서버 시험이 센다. */
    int trackedSessionCount() {
        return trackedBySession.size();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return message;
        }
        if (!isExpired(sessionId)) {
            return message;
        }
        trackedBySession.remove(sessionId);
        meterRegistry.counter("schoolbus.ws.closed", "reason", ErrorCode.TOKEN_EXPIRED.name()).increment();
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(ErrorCode.TOKEN_EXPIRED.name());
        error.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], error.getMessageHeaders());
    }

    /**
     * 열린 세션의 계정을 한 번에 점검해 권한을 잃은 계정의 세션을 만료로 표시한다 — {@link StompStandingSweepScheduler} 가 30초마다 부른다
     * (Ruling 899 M). 방송 송신 스레드({@link #preSend})에서 빼 방송이 DB 를 기다리지 않는다. 점검이 실패해도 예외를 밖으로 내지 않는다
     * — 다음 주기에 다시 한다.
     */
    public void expireLostAccountSessions() {
        try {
            Set<Long> accountIds = trackedBySession.values().stream().map(Tracked::accountId).collect(Collectors.toSet());
            if (accountIds.isEmpty()) {
                return;
            }
            Set<Long> lost = accountStanding.lostAmong(accountIds);
            Instant now = Instant.now(clock);
            trackedBySession.replaceAll((id, tracked) -> lost.contains(tracked.accountId())
                    ? new Tracked(tracked.accountId(), now) : tracked);
        } catch (RuntimeException e) {
            log.warn("열린 WebSocket 세션의 계정 상태 점검에 실패했다 — 다음 주기에 다시 한다", e);
        }
    }

    @EventListener
    public void forget(SessionDisconnectEvent event) {
        trackedBySession.remove(event.getSessionId());
    }
}
