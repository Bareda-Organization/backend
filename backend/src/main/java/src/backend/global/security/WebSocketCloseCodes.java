package src.backend.global.security;

import org.springframework.web.socket.CloseStatus;

/**
 * SUBSCRIBE 인가 실패의 종료 코드 상수(목표 7, API_SPEC §7) — {@link StompAuthChannelInterceptor} 가
 * 실패를 표시하는 세션 속성 키와, 전송 계층({@code ForbiddenSubscriptionCloseFactory})이 그 표식을 보고
 * 실제로 닫을 때 쓸 종료 코드를 함께 둔다. 두 클래스가 이 상수를 공유해야 표식-판독 쌍이 어긋나지 않는다.
 */
public final class WebSocketCloseCodes {

    /** 세션 속성 키 — {@code WebSocketSession#getAttributes()} 와 STOMP 세션 속성이 같은 맵을 공유한다. */
    public static final String FORBIDDEN_SUBSCRIPTION_ATTR = "ws.forbiddenSubscription";

    public static final CloseStatus SUBSCRIPTION_FORBIDDEN = new CloseStatus(4403, "SUBSCRIPTION_FORBIDDEN");

    /** 계정이 채널 권한을 잃어(보호자 연결 해제 · 퇴원 · 기사 교체) 서버가 세션을 닫았다 — 클라이언트는 1초 뒤 다시 붙고, 아직 금지된 채널은 4403 을 받는다. */
    public static final CloseStatus SESSION_REVOKED = new CloseStatus(4500, "SESSION_REVOKED");

    /** 연결 뒤 10초 안에 STOMP CONNECT 가 없어 서버가 닫았다 — 표준 1008(정책 위반)이다. */
    public static final CloseStatus CONNECT_TIMEOUT = CloseStatus.POLICY_VIOLATION.withReason("CONNECT_TIMEOUT");

    private WebSocketCloseCodes() {
    }
}
