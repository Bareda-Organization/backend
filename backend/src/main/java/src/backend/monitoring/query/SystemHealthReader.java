package src.backend.monitoring.query;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.monitoring.dto.AdminDashboardResponse.HealthCell;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.run.scheduler.RunConfirmationScheduler;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * 시스템 상태 4칸(API_SPEC §6.18 {@code health[]}, Ruling 803) — 서버가 이미 가진 값만 읽는다. 새 감시 체계가 아니다: 정식 감시는 운영 경보
 * ({@code DEPLOYMENT §11})이고 이 칸은 메인 관리자에게 보이는 요약이다.
 *
 * <ul>
 *   <li>{@code api} — DB · Redis 연결(Spring 이 이미 가진 헬스 지표). 하나라도 끊기면 {@code down}</li>
 *   <li>{@code position} — 오늘 {@code moving} 회차 중 마지막 수신이 2분을 넘은 것(시작 뒤 한 번도 안 보낸 것 포함, 시작 2분 안은 제외)이 있으면 {@code warn}</li>
 *   <li>{@code confirm_batch} — 확정 배치의 마지막 성공이 2분(30초 폴링 4회분)을 넘으면 {@code down}. 서버 기동 뒤 첫 실행 전에는 기동 시점부터 잰다</li>
 *   <li>{@code notification} — 적재 뒤 5분 넘게 발송되지 않은 알림이 있으면 {@code warn}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class SystemHealthReader {

    static final String API = "api";

    static final String POSITION = "position";

    static final String CONFIRM_BATCH = "confirm_batch";

    static final String NOTIFICATION = "notification";

    private static final String OK = "ok";

    private static final String WARN = "warn";

    private static final String DOWN = "down";

    /** 확정 배치 폴링 30초 × 4회. */
    static final Duration CONFIRM_BATCH_LIMIT = Duration.ofMinutes(2);

    static final Duration NOTIFICATION_LIMIT = Duration.ofMinutes(5);

    /** 한 번에 보는 운행 중 회차 상한 — 목표 규모의 동시 운행 수보다 넉넉하다({@code RunPositionLostGaugeScheduler} 와 같은 값). */
    private static final int MOVING_BATCH = 500;

    private final HealthEndpoint healthEndpoint;

    private final RunRepository runRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    private final RunPositionStore runPositionStore;

    private final RunLiveStateResolver runLiveStateResolver;

    private final SchedulerHealthMetrics schedulerHealthMetrics;

    private final NotificationBacklogReader notificationBacklogReader;

    private final Clock clock;

    List<HealthCell> read() {
        return List.of(api(), position(), confirmBatch(), notification());
    }

    private HealthCell api() {
        List<String> down = new ArrayList<>();
        if (!isUp("db")) {
            down.add("DB");
        }
        if (!isUp("redis")) {
            down.add("Redis");
        }
        return down.isEmpty() ? new HealthCell(API, OK, null)
                : new HealthCell(API, DOWN, String.join("·", down) + " 연결 끊김");
    }

    /** 경로의 헬스 구성 요소가 없거나 조회가 실패하면 살아 있다고 단정하지 않는다. */
    private boolean isUp(String path) {
        try {
            HealthDescriptor descriptor = healthEndpoint.healthForPath(path);
            return descriptor != null && Status.UP.equals(descriptor.getStatus());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private HealthCell position() {
        OffsetDateTime startedBefore = OffsetDateTime.now(clock).minus(StudentBusPositionQueryService.STALE_THRESHOLD);
        LocalDate since = movingRunWindowPolicy.earliestServiceDate();
        List<Run> moving = runRepository.findMovingFromServiceDate(since, 0L, PageRequest.of(0, MOVING_BATCH));
        Map<Long, RunPositionRedisValue> positions = runPositionStore.findAll(moving.stream().map(Run::getId).toList());
        long lost = moving.stream()
                .filter(run -> run.getStartedAt() != null && !run.getStartedAt().isAfter(startedBefore))
                .filter(run -> runLiveStateResolver.resolve(List.of(), positions.get(run.getId())).stale())
                .count();
        return lost == 0 ? new HealthCell(POSITION, OK, null)
                : new HealthCell(POSITION, WARN, "위치 끊김 " + lost + "대");
    }

    private HealthCell confirmBatch() {
        Optional<Duration> sinceLastSuccess = schedulerHealthMetrics
                .sinceLastSuccess(SchedulerHealthMetrics.nameOf(RunConfirmationScheduler.class));
        if (sinceLastSuccess.isEmpty()) {
            return new HealthCell(CONFIRM_BATCH, DOWN, "확정 배치 실행 기록 없음");
        }
        Duration age = sinceLastSuccess.get();
        return age.compareTo(CONFIRM_BATCH_LIMIT) <= 0 ? new HealthCell(CONFIRM_BATCH, OK, null)
                : new HealthCell(CONFIRM_BATCH, DOWN, "확정 배치 마지막 실행 " + age.toMinutes() + "분 전");
    }

    private HealthCell notification() {
        long stale = notificationBacklogReader
                .countPendingCreatedBefore(OffsetDateTime.now(clock).minus(NOTIFICATION_LIMIT));
        return stale == 0 ? new HealthCell(NOTIFICATION, OK, null)
                : new HealthCell(NOTIFICATION, WARN, "발송 지연 " + stale + "건");
    }
}
