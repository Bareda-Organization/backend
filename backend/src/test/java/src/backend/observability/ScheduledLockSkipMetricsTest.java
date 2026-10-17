package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * R59 S-9 — ShedLock 락을 놓쳐 건너뛴 실행은 {@code tasks.scheduled.execution} 의 {@code outcome="SUCCESS"} 로 세지 않고 {@code outcome="SKIPPED"} 로 따로 센다(Ruling 915 B).
 * 기본 계측은 메서드가 예외 없이 돌아오면 성공으로 세는데 ShedLock 은 락을 못 얻으면 메서드를 건너뛰고 정상 반환해, 락을 가진 인스턴스가 멈춰도 다른 인스턴스의 건너뜀이
 * 성공 건수를 올려 "마지막 성공이 오래됨" 경보를 가린다.
 *
 * <p>락 점유는 실제 락 제공자로 한다. 배경 실행은 시험 동안 미뤄져 있으므로 스케줄러가 쓰는 래퍼({@code ScheduledMethodRunnable})를 컨텍스트의 관측 등록기로 직접 돌린다.
 */
@SpringBootTest
@Import(ScheduledLockSkipMetricsTest.LockedSchedulerConfig.class)
class ScheduledLockSkipMetricsTest {

    private static final String LOCK_NAME = "r59-lock-skip-test";

    @Autowired
    private LockedScheduler scheduler;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void 락을_못_얻어_건너뛴_실행은_SUCCESS_가_아니라_SKIPPED_로_센다() throws Exception {
        double successBefore = executions("SUCCESS");
        double skippedBefore = executions("SKIPPED");
        int bodyRunsBefore = scheduler.runCount();
        SimpleLock held = lockProvider.lock(new net.javacrumbs.shedlock.core.LockConfiguration(Instant.now(), LOCK_NAME,
                Duration.ofMinutes(1), Duration.ZERO)).orElseThrow();
        try {
            runOnce();
        } finally {
            held.unlock();
        }

        assertThat(scheduler.runCount()).as("락을 못 얻었으니 본문은 돌지 않았다").isEqualTo(bodyRunsBefore);
        assertThat(executions("SUCCESS") - successBefore).as("건너뛴 실행이 SUCCESS 로 센 횟수").isZero();
        assertThat(executions("SKIPPED") - skippedBefore).as("건너뜀으로 센 횟수").isEqualTo(1.0);
    }

    @Test
    void 락을_얻어_돈_실행은_SUCCESS_로_센다() throws Exception {
        double successBefore = executions("SUCCESS");
        double skippedBefore = executions("SKIPPED");
        int bodyRunsBefore = scheduler.runCount();

        runOnce();

        assertThat(scheduler.runCount() - bodyRunsBefore).as("본문이 돈 횟수").isEqualTo(1);
        assertThat(executions("SUCCESS") - successBefore).isEqualTo(1.0);
        assertThat(executions("SKIPPED") - skippedBefore).isZero();
    }

    private void runOnce() throws Exception {
        Method tick = LockedScheduler.class.getDeclaredMethod("tick");
        new ScheduledMethodRunnable(scheduler, tick, null, () -> observationRegistry).run();
    }

    private double executions(String outcome) {
        return meterRegistry.find("tasks.scheduled.execution")
                .tag("code.namespace", LockedScheduler.class.getCanonicalName()).tag("outcome", outcome).timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }

    /** 락을 쓰는 시험 전용 스케줄러 — 배경 실행은 하지 않는다. */
    static class LockedScheduler {

        private final AtomicInteger runs = new AtomicInteger();

        /** 프록시 너머의 실제 객체가 센 횟수 — 필드를 프록시에서 바로 읽으면 비어 있다. */
        public int runCount() {
            return runs.get();
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
        public void tick() {
            runs.incrementAndGet();
        }
    }

    static class LockedSchedulerConfig {

        @Bean
        LockedScheduler lockedScheduler() {
            return new LockedScheduler();
        }
    }
}
