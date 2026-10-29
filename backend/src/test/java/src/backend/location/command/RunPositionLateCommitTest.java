package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

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

    @MockitoSpyBean
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    void 방송_기록을_비운다() {
        reset(gateway, redis);
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

    @Test
    void 읽은_뒤_쓰기_전에_더_새로운_값이_들어오면_그_값을_덮어쓰지_않는다() throws Exception {
        long runId = RUN_IDS.incrementAndGet();
        String key = "run:" + runId + ":position";
        runPositionStore.save(runId, 값("37.500000", T0.plusSeconds(2)));
        String newer = JsonMapper.builder().build().writeValueAsString(값("37.590000", T0.plusSeconds(9)));
        ValueOperations<String, String> real = redis.opsForValue();
        ValueOperations<String, String> afterRead = Mockito.spy(real);
        AtomicBoolean injected = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            if (injected.compareAndSet(false, true)) {
                real.set(key, newer);
            }
            return read;
        }).when(afterRead).get(key);
        Mockito.doReturn(afterRead).when(redis).opsForValue();

        runPositionStore.save(runId, 값("37.510000", T0.plusSeconds(3)));

        assertThat(injected).as("읽은 직후 새 값이 끼어들었다").isTrue();
        assertThat(runPositionStore.find(runId).orElseThrow().lat())
                .as("읽은 값이 바뀌었으면 쓰지 않고 다시 읽어 판단한다 — 끼어든 더 새로운 값(37.59)이 남아야 한다")
                .isEqualByComparingTo("37.590000");
    }

    @Test
    void 비교_쓰기가_계속_밀려_쓰지_못하면_false_를_돌려주고_이유를_세어_둔다() throws Exception {
        long runId = RUN_IDS.incrementAndGet();
        String key = "run:" + runId + ":position";
        runPositionStore.save(runId, 값("37.500000", T0.plusSeconds(1)));
        ValueOperations<String, String> real = redis.opsForValue();
        ValueOperations<String, String> afterRead = Mockito.spy(real);
        AtomicInteger interlopers = new AtomicInteger();
        // 읽을 때마다 직후에 (우리 위치보다는 옛) 다른 값이 끼어들어 비교 쓰기가 매번 밀린다
        Mockito.doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            real.set(key, JsonMapper.builder().build().writeValueAsString(
                    값("37.500000", T0.plusSeconds(2 + interlopers.getAndIncrement()))));
            return read;
        }).when(afterRead).get(key);
        Mockito.doReturn(afterRead).when(redis).opsForValue();
        double before = 버린_횟수("write_contended");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger storeLogger = (Logger) LoggerFactory.getLogger(RunPositionStore.class);
        storeLogger.addAppender(logs);

        boolean stored;
        try {
            stored = runPositionStore.save(runId, 값("37.510000", T0.plusSeconds(50)));
        } finally {
            storeLogger.detachAppender(logs);
            logs.stop();
        }

        assertThat(stored).as("끼어드는 값 때문에 끝내 쓰지 못했다").isFalse();
        assertThat(interlopers).as("정해진 횟수만 다시 시도하고 멈춘다").hasValue(5);
        assertThat(버린_횟수("write_contended") - before).as("쓰지 못한 위치는 이유와 함께 세어야 한다").isEqualTo(1.0);
        // 분당 한 번 제한기는 컨텍스트의 저장소 빈마다 하나라 이 시험의 첫 실패가 항상 기록된다 — 같은 컨텍스트에서 비교 쓰기를 5번 밀리게 하는 시험을
        // 더하면 이 단언이 빈다
        assertThat(logs.list).as("쓰지 못한 위치는 WARN 으로도 남는다(분당 한 번)")
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("비교 쓰기가 5번 밀려").contains("runId=" + runId));
    }

    private double 버린_횟수(String reason) {
        Counter counter = meterRegistry.find("schoolbus.location.position.dropped").tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
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
