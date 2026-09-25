package src.backend.observability.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.monitoring.query.RunLiveState;
import src.backend.monitoring.query.RunLiveStateResolver;
import src.backend.observability.metrics.RunPositionLostMetrics;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 유실 회차 게이지는 "출발한 지 2분이 지났는데 위치가 2분 넘게 안 들어온" 운행 중 회차만 센다(BR-064 · §13.4).
 * 출발 직후 첫 위치를 기다리는 회차까지 세면 모든 출발이 경고를 한 번씩 울린다.
 */
class RunPositionLostGaugeSchedulerTest {

    private static final Instant NOW = Instant.parse("2030-04-01T03:00:00Z");

    private final RunRepository runRepository = mock(RunRepository.class);

    private final RunLiveStateResolver resolver = mock(RunLiveStateResolver.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RunPositionLostGaugeScheduler scheduler = new RunPositionLostGaugeScheduler(runRepository, resolver,
            new RunPositionLostMetrics(registry), Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    void 출발_2분이_지나고_위치가_끊긴_회차만_센다() {
        Run lost = run(5);
        Run fresh = run(5);
        Run justStarted = run(1);
        given(runRepository.findByStatusAndCanceledAtIsNullOrderByIdAsc(eq(RunStatus.MOVING), any()))
                .willReturn(List.of(lost, fresh, justStarted));
        given(resolver.resolve(eq(lost), any())).willReturn(state(true));
        given(resolver.resolve(eq(fresh), any())).willReturn(state(false));
        given(resolver.resolve(eq(justStarted), any())).willReturn(state(true));

        scheduler.refresh();

        assertThat(registry.get("schoolbus.run.position.lost").gauge().value()).isEqualTo(1.0d);
    }

    private Run run(int startedMinutesAgo) {
        Run run = mock(Run.class);
        given(run.getStartedAt()).willReturn(OffsetDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul"))
                .minusMinutes(startedMinutesAgo));
        return run;
    }

    private RunLiveState state(boolean stale) {
        return new RunLiveState(null, null, null, null, stale, null, null);
    }
}
