package src.backend.global.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * STOMP CONNECT 인증과 계정 상태 게이트를 처리한다(ARCHITECTURE §5.1 3층 중 계정 상태 게이트). CONNECT 는 세션 수립 시 1회만
 * 인증하고, 이후 같은 세션의 모든 메시지는 그때 심어둔 Principal(AuthUser)을 그대로 쓴다. SUBSCRIBE 의 역할 권한 · 자원 소속 검증은
 * {@link StompSubscribeAuthorizer} 가 맡는다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String APP_DESTINATION_PREFIX = "/app/";

    private final JwtTokenProvider tokenProvider;
    private final StompSubscribeAuthorizer subscribeAuthorizer;
    private final StompSessionExpiry sessionExpiry;
    private final MeterRegistry meterRegistry;
    private final AccountStanding accountStanding;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        try {
            return admit(message, accessor);
        } catch (BusinessException e) {
            countRejection(accessor, e);
            throw e;
        }
    }

    /** 접속 · 구독 · 송신 거절을 단계와 사유별로 센다 — 사유는 ERROR 프레임의 message 와 같은 {@link ErrorCode} 이름이다. */
    private void countRejection(StompHeaderAccessor accessor, BusinessException e) {
        String phase = StompCommand.SUBSCRIBE.equals(accessor.getCommand()) ? "subscribe"
                : StompCommand.SEND.equals(accessor.getCommand()) ? "send" : "connect";
        meterRegistry.counter("schoolbus.ws.rejected", "phase", phase, "reason", e.getErrorCode().name()).increment();
        if (e.getErrorCode() == ErrorCode.TOKEN_EXPIRED) {
            meterRegistry.counter("schoolbus.ws.closed", "reason", ErrorCode.TOKEN_EXPIRED.name()).increment();
        }
    }

    private Message<?> admit(Message<?> message, StompHeaderAccessor accessor) {
        // 주의: StompHeaderAccessor.wrap(message)로 얻은 접근자는 새 복사본이라 mutate 해도
        // 원본 message 에 반영되지 않는다. 인바운드 STOMP 채널의 메시지는 mutable 접근자와 함께
        // 만들어지므로, getAccessor 로 "그 접근자"를 그대로 받아와야 setUser 가 세션에 실제로 남는다.
        // STOMP 는 CONNECT 의 별칭 명령이다 — 둘 중 하나만 보면 다른 쪽으로 인증 없이 세션이 선다(BR-112)
        if (StompCommand.CONNECT.equals(accessor.getCommand()) || StompCommand.STOMP.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if (sessionExpiry.isExpired(accessor.getSessionId())) {
            // 연결을 연 토큰이 만료된 세션의 새 구독·송신은 받지 않는다(BR-083)
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            assertStanding(accessor);
            subscribeAuthorizer.authorize(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            assertStanding(accessor);
            rejectBrokerSend(accessor);
        }
        return message;
    }

    /**
     * 열린 세션도 새 구독·송신마다 계정의 상태를 다시 확인한다(Ruling 880) — {@link AccountStanding} 이 최대 5초 보관한 값이고 상태 변경이 커밋되면 바로 비워진다. 퇴사·차단·삭제된 매니저면 받지 않고,
     * 이 세션의 다음 방송도 끊기게 표시한다({@link StompSessionExpiry#expire}).
     */
    private void assertStanding(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof AuthUser user && accountStanding.isLost(user.accountId())) {
            sessionExpiry.expire(accessor.getSessionId());
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
    }

    /**
     * 클라이언트 SEND 는 애플리케이션 목적지({@code /app/**})만 받는다(BR-008). 브로커 목적지
     * ({@code /topic})로 온 SEND 는 심플 브로커가 구독자에게 그대로 배달해
     * 서버 발행분과 구별되지 않는 가짜 위치·비상 이벤트가 되므로 거부한다. {@code /queue}·{@code /user} 는 브로커 접두사가 아니지만
     * {@code /app} 도 아니라 같은 규칙으로 거부된다.
     */
    private void rejectBrokerSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_DESTINATION_PREFIX)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = BearerToken.from(accessor.getFirstNativeHeader(BearerToken.HEADER));
        if (token == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        JwtTokenProvider.Authenticated access = tokenProvider.authenticateWithExpiry(token);
        AuthUser user = access.user();
        if (accountStanding.isLost(user.accountId())) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED); // 새 연결도 같은 규칙(Ruling 880)
        }
        assertActiveAccount(user);
        accessor.setUser(user);
        sessionExpiry.record(accessor.getSessionId(), user.accountId(), access.expiresAt());
    }

    /**
     * 계정 상태 게이트(Ruling 87 이월) — REST 축의 {@code pending} 허용 목록(API_SPEC §1.4:
     * {@code GET /auth/signup-status} · {@code POST /auth/logout} · {@code GET /me} ·
     * {@code POST|DELETE /me/devices})에 WebSocket 으로 대응되는 항목이 없다. STOMP 연결 자체가
     * "실시간 기능 사용"이라 허용할 대상이 없어 <b>전면 거부</b>로 판단했다(REST 허용 목록을 그대로
     * 복제하지 않은 근거).
     *
     * <p>CONNECT 에서 막으면 {@code accessor.setUser} 가 호출되지 않아 이후 SUBSCRIBE 도 인가 주체가
     * 없어 {@link ErrorCode#UNAUTHORIZED} 로 함께 거부된다 — 단일 관문으로 "CONNECT ·
     * SUBSCRIBE 둘 다 거부" 를 만족시킨다(ARCHITECTURE §5.2 가 선호하는 단일 구현 지점과 일치).
     */
    private void assertActiveAccount(AuthUser user) {
        switch (user.status()) {
            case PENDING -> throw new BusinessException(ErrorCode.AUTH_PENDING);
            case REJECTED -> throw new BusinessException(ErrorCode.AUTH_REJECTED);
            case BLOCKED -> throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
            case ACTIVE -> {
            }
        }
        // 임시 비밀번호 강제 변경 표식(Ruling 540) — 변경 전에는 실시간 기능도 쓰지 못한다(REST 게이트와 같은 판정).
        if (user.mustChangePassword()) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
    }
}
