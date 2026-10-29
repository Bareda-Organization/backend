package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.repository.StudentRepository;

/** CONNECT 인증과 SUBSCRIBE 인가의 입력 경계 — 목적지의 숫자 id 가 비정상이어도 일반 거절로 끝나고, 토큰은 한 번만 검증한다. */
class StompAuthChannelInterceptorTest {

    private static final String SECRET = "interceptor-test-secret-interceptor-test-secret-0123456789";

    private final MessageChannel channel = mock(MessageChannel.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private JwtTokenProvider tokenProvider;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenProvider = spy(new JwtTokenProvider(SECRET, 900, 3600, Clock.systemUTC()));
        interceptor = new StompAuthChannelInterceptor(tokenProvider, mock(GuardianChildAccess.class),
                mock(RunAssignmentAccess.class), mock(StudentRepository.class), mock(StompSessionExpiry.class), meters,
                mock(AccountStanding.class));
    }

    @Test
    @DisplayName("long 범위를 넘는 id 의 목적지 구독은 FORBIDDEN 으로 거절되고 4403 표식과 거절 지표가 남는다")
    void 범위를_넘는_id_구독은_FORBIDDEN() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> subscribe = subscribe("/topic/students/99999999999999999999999/run", sessionAttributes);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(sessionAttributes).containsEntry(WebSocketCloseCodes.FORBIDDEN_SUBSCRIPTION_ATTR, Boolean.TRUE);
        assertThat(meters.get("schoolbus.ws.rejected").tags("phase", "subscribe", "reason", "FORBIDDEN").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("매니저·학원 채널의 범위를 넘는 id 도 같은 거절이다")
    void 다른_채널의_범위를_넘는_id도_FORBIDDEN() {
        for (String destination : new String[] {"/topic/manager/runs/99999999999999999999999",
                "/topic/academy/99999999999999999999999/live"}) {
            Message<?> subscribe = subscribe(destination, new HashMap<>());
            assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    @DisplayName("CONNECT 는 토큰의 서명을 한 번만 검증한다")
    void CONNECT_는_토큰을_한_번만_검증한다() {
        String token = tokenProvider.createAccessToken(7L, 1L, Role.STAFF, AccountStatus.ACTIVE);
        Mockito.clearInvocations(tokenProvider);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setSessionId("session-1");

        interceptor.preSend(message(accessor), channel);

        Mockito.verify(tokenProvider, Mockito.times(1)).parse(anyString());
    }

    private static Message<?> subscribe(String destination, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setUser(new AuthUser(7L, 1L, Role.STAFF, AccountStatus.ACTIVE));
        return message(accessor);
    }

    private static Message<?> message(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
