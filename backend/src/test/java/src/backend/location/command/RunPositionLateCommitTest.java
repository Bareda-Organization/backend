package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunPositionStore;

/**
 * 늦게 커밋된 옛 위치가 최신 좌표를 되돌리지 않는다 — 더 새로운 {@code recorded_at} 이 이미 Redis 에 있으면 옛 위치는
 * Redis 최신 좌표도 방송도 바꾸지 못한다. 두 위치 송신이 겹쳐 새 것이 먼저 커밋되는 상황을 위치 수신 이벤트를 순서를
 * 바꿔 발행해 만든다(트랜잭션 밖 발행도 구독자가 도는 {@code fallbackExecution}). 실제 Redis 컨테이너를 쓴다.
 */
@SpringBootTest
class RunPositionLateCommitTest {

    private static final AtomicLong RUN_IDS = new AtomicLong(970_000_000L);

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private RunPositionStore runPositionStore;

    @MockitoSpyBean
    private WebSocketBroadcastGateway gateway;

    @BeforeEach
    void 방송_기록을_비운다() {
        reset(gateway);
    }

    @Test
    void 새_위치가_먼저_커밋된_뒤_도착한_옛_위치는_Redis_최신_좌표와_방송을_바꾸지_못한다() {
        long runId = RUN_IDS.incrementAndGet();

        publisher.publishEvent(이벤트(runId, "37.510000", T0.plusSeconds(4)));
        publisher.publishEvent(이벤트(runId, "37.500000", T0.plusSeconds(2)));

        assertThat(runPositionStore.find(runId).orElseThrow().lat())
                .as("옛 위치(37.50)가 아니라 더 새로운 위치(37.51)가 최신 좌표로 남아야 한다")
                .isEqualByComparingTo("37.510000");
        verify(gateway, times(1)).broadcast(eq("position"), eq(runId), any(), any());
    }

    @Test
    void 옛_위치_다음에_새_위치가_오면_둘_다_방송하고_새_위치가_최신_좌표가_된다() {
        long runId = RUN_IDS.incrementAndGet();

        publisher.publishEvent(이벤트(runId, "37.500000", T0.plusSeconds(2)));
        publisher.publishEvent(이벤트(runId, "37.510000", T0.plusSeconds(4)));

        assertThat(runPositionStore.find(runId).orElseThrow().lat()).isEqualByComparingTo("37.510000");
        verify(gateway, times(2)).broadcast(eq("position"), eq(runId), any(), any());
    }

    @Test
    void 저장소는_더_옛_recorded_at_으로_최신_좌표를_덮어쓰지_않는다() {
        long runId = RUN_IDS.incrementAndGet();

        runPositionStore.save(runId, 값("37.510000", T0.plusSeconds(4)));
        runPositionStore.save(runId, 값("37.500000", T0.plusSeconds(2)));

        assertThat(runPositionStore.find(runId).orElseThrow().lat()).isEqualByComparingTo("37.510000");
    }

    @Test
    void 저장소는_시간대_표기가_달라도_시각으로_비교한다() {
        long runId = RUN_IDS.incrementAndGet();

        runPositionStore.save(runId, 값("37.510000", T0.plusSeconds(4)));
        runPositionStore.save(runId, 값("37.500000", T0.plusSeconds(2).withOffsetSameInstant(java.time.ZoneOffset.UTC)));

        assertThat(runPositionStore.find(runId).orElseThrow().lat()).isEqualByComparingTo("37.510000");
    }

    private static RunPositionReceivedEvent 이벤트(long runId, String lat, OffsetDateTime recordedAt) {
        return new RunPositionReceivedEvent(runId, new BigDecimal(lat), new BigDecimal("127.000000"), recordedAt,
                recordedAt.plusNanos(300_000_000L), 1L, null, null);
    }

    private static RunPositionRedisValue 값(String lat, OffsetDateTime recordedAt) {
        return new RunPositionRedisValue(new BigDecimal(lat), new BigDecimal("127.000000"), recordedAt,
                recordedAt.plusNanos(300_000_000L), null);
    }
}
