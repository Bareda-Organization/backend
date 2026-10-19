package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * Redis 는 살아 있는데 값이 비어 있을 때 — 운영 Redis 는 저장하지 않아 다시 시작하면 다음 송신이 키를 다시 쓸 때까지 운행 중 회차의 최신 좌표가 없다. 그 사이 위치를 비우지 않고(학부모 홈의 버스,
 * 관제 화면의 회차, 비상 신고의 위치 첨부) DB 최신 행을 읽는다. 읽는 범위는 위치가 낡았다고 보는 기준({@link StudentBusPositionQueryService#STALE_THRESHOLD}) 안이고, Redis 에 값이 있으면 DB 를 부르지 않는다.
 */
class RunPositionStoreMissTest {

    private static final long RUN_ID = 7L;

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final RunPositionRepository repository = mock(RunPositionRepository.class);

    private final RunPositionStore store = new RunPositionStore(template, repository, mock(RunStopRepository.class), new SimpleMeterRegistry(), Clock.systemUTC());

    @Test
    @DisplayName("Redis 가 비어 있으면 운행 중 회차의 최신 좌표를 DB 최신 행에서 읽고, 행이 없으면 빈 값이다")
    void 비어_있으면_DB_최신_행을_읽는다() {
        given(template.opsForValue()).willReturn(ops);
        given(ops.multiGet(any())).willReturn(Arrays.asList((String) null));
        RunPosition row = mock(RunPosition.class);
        given(row.getRunId()).willReturn(RUN_ID);
        given(row.getLat()).willReturn(new BigDecimal("37.5"));
        given(row.getLng()).willReturn(new BigDecimal("127.5"));
        given(row.getRecordedAt()).willReturn(OffsetDateTime.now());
        given(row.getReceivedAt()).willReturn(OffsetDateTime.now());
        given(repository.findLatestMovingByRunIdIn(any(), any(), any())).willReturn(List.of(row));

        Map<Long, RunPositionRedisValue> found = store.findAll(List.of(RUN_ID));

        assertThat(found).as("DB 최신 행으로 채운 좌표").containsOnlyKeys(RUN_ID);
        assertThat(found.get(RUN_ID).lat()).isEqualByComparingTo("37.5");
        assertThat(found.get(RUN_ID).currentStopName()).as("정차 항목이 없는 회차의 현재 정차지 이름은 없다").isNull();

        given(repository.findLatestMovingByRunIdIn(any(), any(), any())).willReturn(List.of());
        assertThat(store.findAll(List.of(RUN_ID))).as("DB 에도 행이 없으면 오늘의 답(빈 값)").isEmpty();
        assertThat(store.find(RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("키가 없을 때 읽는 범위는 위치가 낡았다고 보는 기준과 같은 값이다")
    void 범위는_낡음_기준과_같다() {
        assertThat(RunPositionStore.MISS_LOOKBACK).isEqualTo(StudentBusPositionQueryService.STALE_THRESHOLD);
    }

    @Test
    @DisplayName("읽는 구간은 위치가 낡았다고 보는 기준(2분) 안이고, Redis 에 값이 있으면 DB 를 부르지 않는다")
    void 구간은_낡음_기준_안이고_Redis_에_값이_있으면_DB_는_안_부른다() {
        given(template.opsForValue()).willReturn(ops);
        given(ops.multiGet(any())).willReturn(Arrays.asList((String) null));
        given(repository.findLatestMovingByRunIdIn(any(), any(), any())).willReturn(List.of());
        OffsetDateTime before = OffsetDateTime.now();

        store.findAll(List.of(RUN_ID));

        ArgumentCaptor<OffsetDateTime> since = forClass(OffsetDateTime.class);
        verify(repository).findLatestMovingByRunIdIn(any(), since.capture(), any());
        assertThat(Duration.between(since.getValue(), before)).as("조회 하한은 지금보다 낡음 기준만큼 앞")
                .isBetween(StudentBusPositionQueryService.STALE_THRESHOLD.minusSeconds(5), StudentBusPositionQueryService.STALE_THRESHOLD.plusSeconds(5));

        RunPositionRepository untouched = mock(RunPositionRepository.class);
        RunPositionStore withValue = new RunPositionStore(template, untouched, mock(RunStopRepository.class), new SimpleMeterRegistry(), Clock.systemUTC());
        String json = """
                {"lat":37.5,"lng":127.5,"recordedAt":"%s","receivedAt":"%s","currentStopName":"정차지"}
                """.formatted(OffsetDateTime.now(), OffsetDateTime.now());
        given(ops.multiGet(any())).willReturn(List.of(json));

        assertThat(withValue.findAll(List.of(RUN_ID))).containsOnlyKeys(RUN_ID);
        verify(untouched, never()).findLatestMovingByRunIdIn(any(), any(), any());
    }
}
