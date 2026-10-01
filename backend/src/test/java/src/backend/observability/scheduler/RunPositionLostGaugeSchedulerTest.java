package src.backend.observability.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.monitoring.query.RunLiveStateResolver;
import src.backend.observability.metrics.RunPositionLostMetrics;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 유실 회차 게이지는 "출발한 지 2분이 지났는데 위치가 2분 넘게 안 들어온" 운행 중 회차만 센다(BR-064 · §13.4).
 * 출발 직후 첫 위치를 기다리는 회차까지 세면 모든 출발이 경고를 한 번씩 울린다.
 */
class RunPositionLostGaugeSchedulerTest {

    private static final Instant NOW = Instant.parse("2030-04-01T03:00:00Z");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RunRepository runRepository = mock(RunRepository.class);

    private final RunPositionStore runPositionStore = mock(RunPositionStore.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final Clock clock = Clock.fixed(NOW, SEOUL);

    private final RunPositionLostGaugeScheduler scheduler = new RunPositionLostGaugeScheduler(runRepository,
            new MovingRunWindowPolicy(clock), new RunLiveStateResolver(clock), runPositionStore,
            new RunPositionLostMetrics(registry), clock);

    @Test
    void 출발_2분이_지나고_위치가_끊긴_회차만_센다() {
        Run lost = run(1L, 5);
        Run fresh = run(2L, 5);
        Run justStarted = run(3L, 1);
        // 조회 범위는 어제부터다(Ruling 701) — 시계가 2030-04-01(서울)이니 2030-03-31 이 아니면 빈 목록이 돌아온다
        given(runRepository.findMovingFromServiceDate(eq(LocalDate.of(2030, 3, 31)), eq(0L), any()))
                .willReturn(List.of(lost, fresh, justStarted));
        given(runPositionStore.findAll(List.of(1L, 2L, 3L)))
                .willReturn(Map.of(1L, receivedMinutesAgo(3), 2L, receivedMinutesAgo(0)));

        scheduler.refresh();

        assertThat(registry.get("schoolbus.run.position.lost").gauge().value()).isEqualTo(1.0d);
    }

    private Run run(long id, int startedMinutesAgo) {
        Run run = mock(Run.class);
        given(run.getId()).willReturn(id);
        given(run.getStartedAt()).willReturn(OffsetDateTime.ofInstant(NOW, SEOUL).minusMinutes(startedMinutesAgo));
        return run;
    }

    private RunPositionRedisValue receivedMinutesAgo(int minutes) {
        OffsetDateTime receivedAt = OffsetDateTime.ofInstant(NOW, SEOUL).minusMinutes(minutes);
        return new RunPositionRedisValue(BigDecimal.ONE, BigDecimal.ONE, receivedAt, receivedAt, null);
    }
}
