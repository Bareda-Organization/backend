package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

import src.backend.location.infrastructure.RunPositionStore;
import src.backend.monitoring.dto.AdminDashboardResponse.HealthCell;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.repository.RunRepository;
import src.backend.run.scheduler.RunConfirmationScheduler;

/**
 * 시스템 상태 판정(API_SPEC §6.18 {@code health[]}, Ruling 803)의 "정상이 아닌" 갈래 — DB·Redis 연결 끊김, 확정 배치 정지, 발송 지연. 통합 시험
 * ({@code AdminDashboardControllerTest})은 실제 컨텍스트에서 정상 · 위치 끊김 · 발송 지연을 본다.
 */
class SystemHealthReaderTest {

    private static final String CONFIRM_SCHEDULER = SchedulerHealthMetrics.nameOf(RunConfirmationScheduler.class);

    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);

    private final SchedulerHealthMetrics schedulerHealthMetrics = mock(SchedulerHealthMetrics.class);

    private final NotificationBacklogReader notificationBacklogReader = mock(NotificationBacklogReader.class);

    private SystemHealthReader reader;

    private IndicatedHealthDescriptor up;

    private IndicatedHealthDescriptor down;

    @BeforeEach
    void setUp() {
        // 목 안에서 다른 목을 스텁하면 중첩 스텁 오류가 나므로 먼저 만들어 둔다
        up = descriptor(Status.UP);
        down = descriptor(Status.DOWN);
        Clock clock = Clock.fixed(Instant.parse("2030-04-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        reader = new SystemHealthReader(healthEndpoint, mock(RunRepository.class), mock(MovingRunWindowPolicy.class),
                mock(RunPositionStore.class), mock(RunLiveStateResolver.class), schedulerHealthMetrics,
                notificationBacklogReader, clock);
        정상으로_맞춘다();
    }

    private void 정상으로_맞춘다() {
        when(healthEndpoint.healthForPath("db")).thenReturn(up);
        when(healthEndpoint.healthForPath("redis")).thenReturn(up);
        when(schedulerHealthMetrics.sinceLastSuccess(CONFIRM_SCHEDULER)).thenReturn(Optional.of(Duration.ofSeconds(30)));
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
    @DisplayName("confirm_batch — 마지막 성공이 정확히 2분이면 ok, 2분을 넘으면 down, 기록이 없으면 down")
    void 확정_배치는_2분을_넘으면_down() {
        when(schedulerHealthMetrics.sinceLastSuccess(CONFIRM_SCHEDULER)).thenReturn(Optional.of(Duration.ofSeconds(120)));
        assertThat(cell("confirm_batch").status()).isEqualTo("ok");

        when(schedulerHealthMetrics.sinceLastSuccess(CONFIRM_SCHEDULER)).thenReturn(Optional.of(Duration.ofSeconds(121)));
        assertThat(cell("confirm_batch")).isEqualTo(new HealthCell("confirm_batch", "down", "확정 배치 마지막 실행 2분 전"));

        when(schedulerHealthMetrics.sinceLastSuccess(CONFIRM_SCHEDULER)).thenReturn(Optional.empty());
        assertThat(cell("confirm_batch").status()).isEqualTo("down");
    }

    @Test
    @DisplayName("notification — 5분 넘게 대기한 알림이 있으면 warn 과 건수")
    void 발송_지연이_있으면_warn() {
        when(notificationBacklogReader.countPendingCreatedBefore(any())).thenReturn(3L);

        assertThat(cell("notification")).isEqualTo(new HealthCell("notification", "warn", "발송 지연 3건"));
    }
}
