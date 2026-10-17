package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import testsupport.clock.AdvanceableClock;

/**
 * 열린 STOMP 세션의 계정 상태 점검(Ruling 880 · R57 RT-7 / Ruling 899 M) — 방송 송신 스레드({@code preSend})는 DB 를 묻지 않고(방송은 초당 수만 건),
 * 점검은 {@link StompStandingSweepScheduler} 가 30초마다 묶음으로 한다. 점검이 실패해도 방송은 막히지 않는다. 고정 시계 위에서 시각만 옮기고, DB 대신
 * {@link AccountStanding} 가짜를 쓴다. 실서버 경로(퇴사 → 세션 종료)는 {@link StompAccountStandingTest} 가 본다.
 */
class StompSessionExpirySweepTest {

    private static final Instant START = Instant.parse("2026-10-11T00:00:00Z");
    private static final long ACCOUNT = 7L;

    private AdvanceableClock clock;
    private AccountStanding accountStanding;
    private StompSessionExpiry expiry;

    @BeforeEach
    void setUp() {
        clock = new AdvanceableClock(Clock.fixed(START, ZoneOffset.UTC));
        accountStanding = mock(AccountStanding.class);
        expiry = new StompSessionExpiry(clock, accountStanding, new SimpleMeterRegistry());
        expiry.record("s1", ACCOUNT, START.plus(Duration.ofHours(1)));
    }

    @Test
    void 방송_송신은_시간이_얼마나_지나도_계정_상태를_묻지_않는다() {
        for (int i = 0; i < 5; i++) {
            expiry.preSend(broadcast("s1"), null);
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 5; i++) {
            expiry.preSend(broadcast("s1"), null);
        }

        verify(accountStanding, never()).lostAmong(anyCollection());
    }

    @Test
    void 점검은_열린_세션의_계정을_묶어_한_번에_묻는다() {
        expiry.record("s2", 8L, START.plus(Duration.ofHours(1)));
        expiry.record("s3", ACCOUNT, START.plus(Duration.ofHours(1))); // 같은 계정의 두 번째 기기

        expiry.expireLostAccountSessions();

        verify(accountStanding, times(1)).lostAmong(Set.of(ACCOUNT, 8L));
    }

    @Test
    void 점검이_예외를_던져도_밖으로_퍼지지_않고_방송은_그대로_배달된다() {
        when(accountStanding.lostAmong(anyCollection())).thenThrow(new IllegalStateException("DB 장애"));
        Message<?> message = broadcast("s1");

        assertThatCode(expiry::expireLostAccountSessions).doesNotThrowAnyException();

        assertThat(expiry.preSend(message, null)).as("점검 실패가 방송을 막지 않는다").isSameAs(message);
    }

    /** 대조군 — 점검이 권한 잃은 계정을 알리면 그 계정의 세션만 ERROR 프레임으로 바뀌고 다른 계정의 세션은 그대로다. */
    @Test
    void 권한을_잃은_계정의_세션만_방송_대신_ERROR_프레임이_된다() {
        expiry.record("s2", 8L, START.plus(Duration.ofHours(1)));
        when(accountStanding.lostAmong(anyCollection())).thenReturn(Set.of(ACCOUNT));
        Message<?> other = broadcast("s2");

        expiry.expireLostAccountSessions();
        Message<?> lost = expiry.preSend(broadcast("s1"), null);

        assertThat(StompHeaderAccessor.wrap(lost).getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(StompHeaderAccessor.wrap(lost).getMessage()).isEqualTo("TOKEN_EXPIRED");
        assertThat(expiry.preSend(other, null)).isSameAs(other);
    }

    private static Message<?> broadcast(String sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
