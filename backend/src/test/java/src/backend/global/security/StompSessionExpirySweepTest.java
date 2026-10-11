package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
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

import testsupport.clock.AdvanceableClock;

/**
 * 열린 STOMP 세션의 계정 상태 점검 <b>간격 · 실패 경로</b>(Ruling 880) — 방송은 초당 수만 건이라 방송마다 DB 를 물으면 안 되고(간격 안에서는
 * 점검 1회), 점검이 실패해도 방송은 막히지 않는다. 고정 시계 위에서 시각만 옮기고, DB 대신 {@link AccountStanding} 가짜를 쓴다.
 * 실서버 경로(퇴사 → 세션 종료)는 {@link StompAccountStandingTest} 가 본다.
 */
class StompSessionExpirySweepTest {

    private static final Instant START = Instant.parse("2026-10-11T00:00:00Z");
    private static final Duration WITHIN_INTERVAL = Duration.ofSeconds(29);
    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final long ACCOUNT = 7L;

    private AdvanceableClock clock;
    private AccountStanding accountStanding;
    private StompSessionExpiry expiry;

    @BeforeEach
    void setUp() {
        clock = new AdvanceableClock(Clock.fixed(START, ZoneOffset.UTC));
        accountStanding = mock(AccountStanding.class);
        expiry = new StompSessionExpiry(clock, accountStanding);
        expiry.record("s1", ACCOUNT, START.plus(Duration.ofHours(1)));
    }

    @Test
    void 간격_안의_방송은_몇_건이든_점검_1회이고_간격이_지나면_다시_점검한다() {
        for (int i = 0; i < 5; i++) {
            expiry.preSend(broadcast("s1"), null);
        }
        clock.advance(WITHIN_INTERVAL);
        for (int i = 0; i < 5; i++) {
            expiry.preSend(broadcast("s1"), null);
        }
        verify(accountStanding, times(1)).lostAmong(anyCollection());

        clock.advance(INTERVAL.minus(WITHIN_INTERVAL)); // 첫 점검으로부터 정확히 30초
        expiry.preSend(broadcast("s1"), null);

        verify(accountStanding, times(2)).lostAmong(anyCollection());
    }

    @Test
    void 점검이_예외를_던져도_방송은_그대로_배달되고_다음_간격에_다시_점검한다() {
        when(accountStanding.lostAmong(anyCollection())).thenThrow(new IllegalStateException("DB 장애"));
        Message<?> message = broadcast("s1");

        assertThat(expiry.preSend(message, null)).as("점검 실패가 방송을 막지 않는다").isSameAs(message);

        // 실패한 점검도 간격을 쓴다 — DB 가 죽은 동안 방송마다 다시 묻지 않는다(회수가 최대 30초 늦는 대가로 장애 중 DB 를 두드리지 않는다)
        clock.advance(WITHIN_INTERVAL);
        assertThat(expiry.preSend(message, null)).isSameAs(message);
        verify(accountStanding, times(1)).lostAmong(anyCollection());

        clock.advance(INTERVAL.minus(WITHIN_INTERVAL));
        assertThat(expiry.preSend(message, null)).isSameAs(message);
        verify(accountStanding, times(2)).lostAmong(anyCollection());
    }

    /** 대조군 — 점검이 권한 잃은 계정을 알리면 그 세션만 ERROR 프레임으로 바뀌고 다른 계정의 세션은 그대로다. */
    @Test
    void 권한을_잃은_계정의_세션만_방송_대신_ERROR_프레임이_된다() {
        expiry.record("s2", 8L, START.plus(Duration.ofHours(1)));
        when(accountStanding.lostAmong(anyCollection())).thenReturn(Set.of(ACCOUNT));
        Message<?> other = broadcast("s2");

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
