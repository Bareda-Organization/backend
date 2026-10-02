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
import java.util.stream.Collectors;
import java.util.stream.LongStream;

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

    /** 출발한 지 2분이 넘었는데 Redis 에 위치 키가 한 번도 없는 회차 — 이 경보가 잡으려는 대표 상황이다. */
    @Test
    void 위치가_한_번도_안_온_회차도_유실로_센다() {
        Run neverReported = run(1L, 5);
        given(runRepository.findMovingFromServiceDate(eq(LocalDate.of(2030, 3, 31)), eq(0L), any()))
                .willReturn(List.of(neverReported));
        given(runPositionStore.findAll(List.of(1L))).willReturn(Map.of());

        scheduler.refresh();

        assertThat(registry.get("schoolbus.run.position.lost").gauge().value()).isEqualTo(1.0d);
    }

    /** BR-011 형태 — 첫 묶음만 세면 그 뒤 회차의 유실이 지표에 안 잡힌다. 유실 회차를 둘째 묶음에만 둔다(BR-342). */
    @Test
    void 한_묶음을_넘는_회차는_다음_묶음까지_이어_읽어_센다() {
        LocalDate since = LocalDate.of(2030, 3, 31);
        List<Long> firstIds = LongStream.rangeClosed(1, RunPositionLostGaugeScheduler.BATCH_SIZE).boxed().toList();
        long lostId = RunPositionLostGaugeScheduler.BATCH_SIZE + 1L;
        List<Run> firstBatch = firstIds.stream().map(id -> run(id, 5)).toList();
        Run lostInSecondBatch = run(lostId, 5);
        Map<Long, RunPositionRedisValue> fresh = firstIds.stream()
                .collect(Collectors.toMap(id -> id, id -> receivedMinutesAgo(0)));
        // 목 호출(getId)을 given 안에서 부르면 스터빙이 끝나지 않은 채로 겹치므로 값은 미리 계산해 둔다
        given(runRepository.findMovingFromServiceDate(eq(since), eq(0L), any())).willReturn(firstBatch);
        given(runRepository.findMovingFromServiceDate(eq(since), eq(firstIds.get(firstIds.size() - 1)), any()))
                .willReturn(List.of(lostInSecondBatch));
        given(runPositionStore.findAll(firstIds)).willReturn(fresh);
        given(runPositionStore.findAll(List.of(lostId))).willReturn(Map.of(lostId, receivedMinutesAgo(3)));

        scheduler.refresh();

        assertThat(registry.get("schoolbus.run.position.lost").gauge().value())
                .as("첫 묶음 500건은 모두 신선하고 유실은 둘째 묶음의 1건뿐이다").isEqualTo(1.0d);
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
