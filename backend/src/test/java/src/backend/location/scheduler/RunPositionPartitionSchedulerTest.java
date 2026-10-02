package src.backend.location.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.observability.metrics.SchedulerHealthMetrics;

/**
 * 위치 이력 파티션 미리 만들기의 배선(R46-LATERBE B-1) — 오늘 날짜(주입된 시계 기준)부터 {@code LOOK_AHEAD_DAYS} 일 뒤까지를
 * 관리자에게 맡기고, 기동 직후 실행은 실패해도 기동을 막지 않으며 설정으로 끌 수 있다. 파티션 생성 자체는
 * {@code RunPositionPartitionManagerTest} 가 실제 DB 로 본다.
 */
class RunPositionPartitionSchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2032-06-15T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    private final RunPositionPartitionManager manager = mock(RunPositionPartitionManager.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final SchedulerHealthMetrics metrics = new SchedulerHealthMetrics(registry);

    private final RunPositionPartitionScheduler scheduler = new RunPositionPartitionScheduler(manager, metrics, clock);

    @Test
    void 매일_실행은_시계의_오늘부터_7일_뒤까지_만들도록_관리자를_부른다() {
        scheduler.ensureAhead();

        // 15:30Z = 한국 시간 6/16 00:30 — 날짜는 UTC 가 아니라 주입된 시계(Asia/Seoul)가 정한다
        verify(manager).ensureAhead(LocalDate.of(2032, 6, 16), RunPositionPartitionScheduler.LOOK_AHEAD_DAYS);
    }

    @Test
    void 기동_직후_실행은_설정이_켜져_있을_때만_돈다() {
        scheduler.ensureOnStartup();
        verify(manager, never()).ensureAhead(any(), anyInt());

        ReflectionTestUtils.setField(scheduler, "onStartup", true);
        scheduler.ensureOnStartup();
        verify(manager).ensureAhead(LocalDate.of(2032, 6, 16), RunPositionPartitionScheduler.LOOK_AHEAD_DAYS);
    }

    @Test
    void 기동_직후_실패는_기동을_막지_않고_실패_카운터만_올린다() {
        ReflectionTestUtils.setField(scheduler, "onStartup", true);
        willThrow(new IllegalStateException("db down")).given(manager).ensureAhead(any(), anyInt());

        scheduler.ensureOnStartup(); // 예외가 새면 이 시험이 실패한다

        // 경보는 매일 실행 쪽 "마지막 성공 이후 경과" 만 보므로, 기동 직후 실패를 드러내는 신호는 이 카운터뿐이다(BR-343)
        org.assertj.core.api.Assertions
                .assertThat(registry.counter("schoolbus.scheduler.failures", "scheduler", "run-position-partition").count())
                .isEqualTo(1.0d);
    }

    @Test
    void 매일_실행은_스케줄과_분산_잠금이_붙어_있다() throws NoSuchMethodException {
        Method method = RunPositionPartitionScheduler.class.getMethod("ensureAhead");

        org.assertj.core.api.Assertions.assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(method.getAnnotation(SchedulerLock.class)).isNotNull();
    }
}
