package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.location.infrastructure.RunPositionStore;
import src.backend.monitoring.dto.AdminDashboardResponse.HealthCell;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.repository.RunRepository;
import src.backend.run.scheduler.ConfirmationBatchHeartbeat;
import testsupport.clock.SettableClock;

/**
 * 시스템 상태 판정(API_SPEC §6.18 {@code health[]}, Ruling 803)의 "정상이 아닌" 갈래 — DB·Redis 연결 끊김, 확정 배치 정지, 발송 지연. 통합 시험
 * ({@code AdminDashboardControllerTest})은 실제 컨텍스트에서 정상 · 위치 끊김 · 발송 지연을 본다.
 *
 * <p>확정 배치 칸은 락을 쥔 인스턴스가 남기는 공유 마지막 성공 시각을 읽는다 — 두 인스턴스가 같은 공유 저장소(여기서는 맵으로 흉내 낸 Redis)를 본다.
 */
class SystemHealthReaderTest {

    private static final Instant START = Instant.parse("2030-04-01T03:00:00Z");

    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);

    private final NotificationBacklogReader notificationBacklogReader = mock(NotificationBacklogReader.class);

    private final Map<String, String> sharedStore = new HashMap<>();

    private SettableClock clock;

    private ConfirmationBatchHeartbeat heartbeat;

    private SystemHealthReader reader;

    private IndicatedHealthDescriptor up;

    private IndicatedHealthDescriptor down;

    @BeforeEach
    void setUp() {
        // 목 안에서 다른 목을 스텁하면 중첩 스텁 오류가 나므로 먼저 만들어 둔다
        up = descriptor(Status.UP);
        down = descriptor(Status.DOWN);
        clock = new SettableClock(START, ZoneId.of("Asia/Seoul"));
        heartbeat = new ConfirmationBatchHeartbeat(sharedRedis(), clock);
        reader = readerOf(heartbeat);
        정상으로_맞춘다();
    }

    private SystemHealthReader readerOf(ConfirmationBatchHeartbeat of) {
        return new SystemHealthReader(healthEndpoint, mock(RunRepository.class), mock(MovingRunWindowPolicy.class),
                mock(RunPositionStore.class), mock(RunLiveStateResolver.class), of, notificationBacklogReader, clock);
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate sharedRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        doAnswer(invocation -> {
            sharedStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(operations.get(anyString())).thenAnswer(invocation -> sharedStore.get(invocation.<String>getArgument(0)));
        return redis;
    }

    private void 정상으로_맞춘다() {
        when(healthEndpoint.healthForPath("db")).thenReturn(up);
        when(healthEndpoint.healthForPath("redis")).thenReturn(up);
        when(notificationBacklogReader.countPendingCreatedBefore(any())).thenReturn(0L);
    }

    private static IndicatedHealthDescriptor descriptor(Status status) {
        // HealthDescriptor 는 봉인된 추상 클래스라 직접 못 만든다 — 구체 하위 타입(final)을 인라인 목으로 쓴다
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(status);
        return descriptor;
    }

    private HealthCell cell(String key) {
        return reader.read().stream().filter(cell -> cell.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("전부 정상이면 4칸이 ok 이고 detail 은 null")
    void 정상이면_ok() {
        List<HealthCell> cells = reader.read();

        assertThat(cells).extracting(HealthCell::key).containsExactly("api", "position", "confirm_batch", "notification");
        assertThat(cells).extracting(HealthCell::status).containsOnly("ok");
        assertThat(cells).extracting(HealthCell::detail).containsOnlyNulls();
    }

    @Test
    @DisplayName("api — DB 또는 Redis 가 하나라도 끊기면(또는 확인할 수 없으면) down 이다")
    void api_는_하나라도_끊기면_down() {
        when(healthEndpoint.healthForPath("db")).thenReturn(down);
        assertThat(cell("api")).isEqualTo(new HealthCell("api", "down", "DB 연결 끊김"));

        when(healthEndpoint.healthForPath("redis")).thenReturn(null);
        assertThat(cell("api")).isEqualTo(new HealthCell("api", "down", "DB·Redis 연결 끊김"));

        when(healthEndpoint.healthForPath("db")).thenReturn(up);
        when(healthEndpoint.healthForPath("redis")).thenThrow(new IllegalStateException("조회 실패"));
        assertThat(cell("api")).isEqualTo(new HealthCell("api", "down", "Redis 연결 끊김"));
    }

    @Test
    @DisplayName("confirm_batch — 마지막 성공이 정확히 2분이면 ok, 2분을 넘으면 down")
    void 확정_배치는_2분을_넘으면_down() {
        clock.set(START.plusSeconds(120));
        assertThat(cell("confirm_batch").status()).isEqualTo("ok");

        clock.set(START.plusSeconds(121));
        assertThat(cell("confirm_batch")).isEqualTo(new HealthCell("confirm_batch", "down", "확정 배치 마지막 실행 2분 전"));
    }

    @Test
    @DisplayName("confirm_batch — 락을 쥐지 못해 한 번도 돌지 않은 인스턴스도 락을 쥔 인스턴스가 성공하는 동안은 ok 다")
    void 락을_못_쥔_인스턴스는_공유_성공_시각으로_판정한다() {
        SystemHealthReader holderOnly = reader;
        ConfirmationBatchHeartbeat otherHeartbeat = new ConfirmationBatchHeartbeat(sharedRedis(), clock);
        SystemHealthReader neverRuns = readerOf(otherHeartbeat);

        for (int tick = 1; tick <= 20; tick++) {
            clock.set(START.plusSeconds(30L * tick));
            heartbeat.markSuccess();
        }

        assertThat(holderOnly.read()).extracting(HealthCell::status).containsOnly("ok");
        assertThat(neverRuns.read().get(2)).as("10분 넘게 한 번도 안 돈 인스턴스가 down 이면 락 한 대 운영이 늘 경보를 낸다")
                .isEqualTo(new HealthCell("confirm_batch", "ok", null));
    }

    @Test
    @DisplayName("confirm_batch — 어느 인스턴스도 2분 안에 성공하지 못하면 두 인스턴스 모두 down 이다")
    void 아무도_성공하지_못하면_둘_다_down() {
        ConfirmationBatchHeartbeat otherHeartbeat = new ConfirmationBatchHeartbeat(sharedRedis(), clock);
        SystemHealthReader other = readerOf(otherHeartbeat);
        clock.set(START.plusSeconds(30));
        heartbeat.markSuccess();

        clock.set(START.plusSeconds(30 + 121));

        assertThat(cell("confirm_batch").status()).isEqualTo("down");
        assertThat(other.read().get(2).status()).isEqualTo("down");
    }

    @Test
    @DisplayName("confirm_batch — Redis 가 없어도 한 대 운영은 자기 마지막 성공 시각으로 판정한다")
    void 레디스가_없어도_한_대는_동작한다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new IllegalStateException("Redis 끊김"));
        ConfirmationBatchHeartbeat alone = new ConfirmationBatchHeartbeat(broken, clock);
        reader = readerOf(alone);

        clock.set(START.plusSeconds(100));
        alone.markSuccess();
        clock.set(START.plusSeconds(100 + 119));

        assertThat(cell("confirm_batch").status()).isEqualTo("ok");
        clock.set(START.plusSeconds(100 + 121));
        assertThat(cell("confirm_batch").status()).isEqualTo("down");
    }

    @Test
    @DisplayName("confirm_batch — Redis 가 계속 실패해도 같은 경고는 쓰기 · 읽기 각각 분당 한 줄만 남긴다")
    void 레디스_실패_경고는_분당_한_줄이다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new IllegalStateException("Redis 끊김"));
        ConfirmationBatchHeartbeat alone = new ConfirmationBatchHeartbeat(broken, clock);
        reader = readerOf(alone);
        Logger logger = (Logger) LoggerFactory.getLogger(ConfirmationBatchHeartbeat.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int tick = 0; tick < 5; tick++) {
                alone.markSuccess();
                reader.read();
            }
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .as("30초 틱과 대시보드 읽기마다 스택이 붙은 경고가 쌓이면 Redis 장애 중 로그가 범람한다").hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("notification — 5분 넘게 대기한 알림이 있으면 warn 과 건수")
    void 발송_지연이_있으면_warn() {
        when(notificationBacklogReader.countPendingCreatedBefore(any())).thenReturn(3L);

        assertThat(cell("notification")).isEqualTo(new HealthCell("notification", "warn", "발송 지연 3건"));
    }
}
