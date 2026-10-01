package src.backend.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.location.command.RunPositionRedisListener;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.location.proximity.ProximityNotificationService;
import src.backend.location.repository.RunPositionRepository;
import src.backend.location.scheduler.ProximityNotificationScheduler;
import src.backend.observability.metrics.RunPositionFallbackMetrics;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * Redis 가 죽은 동안 같은 실패가 요청·틱마다 스택트레이스째 로그에 쏟아지지 않는다(R46 S-3) — 위치 수신 리스너(기사 100대 ×
 * 0.5/s) · 최신 좌표 읽기 대체 · 근접 판정 스케줄러 세 곳이 초당 수십~백 줄의 스택을 내면, {@code awslogs} 가 블로킹
 * 모드일 때 로그 전송 지연이 로그를 남기는 모든 스레드를 멈출 수 있다. 같은 곳의 반복 실패는 <b>분당 한 번만</b> 스택과 함께
 * 남기고 나머지는 센다.
 */
class RedisFailureLogFloodTest {

    private static final int FAILURES = 60;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private Logger logger;

    @BeforeEach
    void 로그를_모은다() {
        appender.start();
    }

    @AfterEach
    void 모으기를_끝낸다() {
        if (logger != null) {
            logger.detachAppender(appender);
        }
        appender.stop();
    }

    @Test
    @DisplayName("위치 수신 리스너 — Redis 쓰기 실패가 60번 이어져도 스택은 1건이다")
    void 위치_수신_리스너는_스택을_한_번만_남긴다() {
        RunPositionStore store = mock(RunPositionStore.class);
        doThrow(new RedisConnectionFailureException("redis down")).when(store).save(any(), any());
        RunPositionRedisListener listener = new RunPositionRedisListener(store);
        collect(RunPositionRedisListener.class);

        for (int i = 0; i < FAILURES; i++) {
            listener.updateRedisAfterCommit(new RunPositionReceivedEvent(1L, BigDecimal.ONE, BigDecimal.ONE,
                    OffsetDateTime.now(), OffsetDateTime.now(), 1L, "정차지", null));
        }

        assertStackLogged(1);
    }

    @Test
    @DisplayName("최신 좌표 읽기 — Redis 읽기 실패가 60번 이어져도 스택은 1건이고 DB 대체는 매번 한다")
    void 최신_좌표_읽기는_스택을_한_번만_남기고_대체는_계속한다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("redis down"));
        RunPositionRepository history = mock(RunPositionRepository.class);
        when(history.findLatestByRunIdIn(any())).thenReturn(List.of());
        RunPositionStore store = new RunPositionStore(redis, history, mock(RunPositionFallbackMetrics.class));
        collect(RunPositionStore.class);

        for (int i = 0; i < FAILURES; i++) {
            store.findAll(List.of(1L));
        }

        assertStackLogged(1);
        org.mockito.Mockito.verify(history, org.mockito.Mockito.times(FAILURES)).findLatestByRunIdIn(any());
    }

    @Test
    @DisplayName("근접 판정 스케줄러 — 판정 실패가 60틱 이어져도 스택은 판정별 1건이다")
    void 근접_판정_스케줄러는_스택을_한_번만_남긴다() {
        RunRepository runRepository = mock(RunRepository.class);
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(1L);
        when(run.getAcademyId()).thenReturn(1L);
        when(runRepository.findMovingFromServiceDate(any(LocalDate.class), any(Long.class), any(PageRequest.class)))
                .thenReturn(List.of(run));
        ProximityNotificationService service = mock(ProximityNotificationService.class);
        RunPositionStore store = mock(RunPositionStore.class);
        when(store.findAll(any())).thenReturn(Map.of(1L, new RunPositionRedisValue(new BigDecimal("37.5"),
                new BigDecimal("127.0"), OffsetDateTime.now(), OffsetDateTime.now(), null)));
        // 서비스가 판정 둘의 실패를 각각 알려 온다 — 근접·출발이 따로 센다
        doAnswer(invocation -> {
            BiConsumer<ProximityNotificationService.Judgment, Exception> onFailure = invocation.getArgument(3);
            onFailure.accept(ProximityNotificationService.Judgment.APPROACH, new IllegalStateException("판정 실패"));
            onFailure.accept(ProximityNotificationService.Judgment.DEPARTURE, new IllegalStateException("판정 실패"));
            return null;
        }).when(service).judgeRun(any(), any(), any(), any());
        ProximityNotificationScheduler scheduler = new ProximityNotificationScheduler(runRepository,
                new MovingRunWindowPolicy(Clock.systemUTC()), service, store, mock(SchedulerHealthMetrics.class));
        collect(ProximityNotificationScheduler.class);

        for (int i = 0; i < FAILURES; i++) {
            scheduler.judgeMovingRuns();
        }

        assertStackLogged(2); // 근접 판정 1건 + 출발 판정 1건 — 판정마다 따로 센다
    }

    private void collect(Class<?> owner) {
        logger = (Logger) LoggerFactory.getLogger(owner);
        logger.addAppender(appender);
    }

    /** 경고는 남되(원인 추적용) 스택트레이스가 붙은 건 호출 위치당 분당 1건이다. */
    private void assertStackLogged(int expected) {
        long withStack = appender.list.stream().filter(event -> event.getThrowableProxy() != null).count();
        assertThat(appender.list).as("원인 추적용 경고는 남는다").isNotEmpty();
        assertThat(withStack).as("스택트레이스가 붙은 로그 — 60번 실패해도 호출 위치당 분당 1건").isEqualTo(expected);
        assertThat(appender.list.size()).as("전체 경고 줄도 실패 횟수만큼이 아니다").isLessThan(FAILURES);
    }
}
