package src.backend.location.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.observability.metrics.SchedulerHealthMetrics;

/**
 * {@code run_position} 일 단위 파티션을 앞으로 쓸 날짜까지 미리 만든다(R46-LATERBE B-1, Ruling 670) — 기동 직후 한 번과 매일 한 번.
 *
 * <p>미리 만들기가 멈춰도 위치 수신은 기본 파티션({@code run_position_default})이 받아 이어진다. 그래서 여유를
 * {@link #LOOK_AHEAD_DAYS} 일로 두어, 하루 이틀 실패해도 파티션이 없는 날이 오지 않게 한다. 매일 도는 쪽의 실패는
 * {@code ScheduledTaskMetricsAspect} 가 실패 카운터와 "마지막 성공 이후 경과" 게이지로 드러내고(scheduler 태그
 * {@code run-position-partition}), 기동 직후 쪽은 기동을 막지 않고 같은 실패 카운터만 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPositionPartitionScheduler {

    /** 오늘부터 며칠 뒤까지 파티션을 만들어 두는가 — 미리 만들기가 이 기간만큼 멈춰도 위치 수신에 영향이 없다. */
    static final int LOOK_AHEAD_DAYS = 7;

    private final RunPositionPartitionManager partitionManager;

    private final SchedulerHealthMetrics schedulerHealthMetrics;

    private final Clock clock;

    /** 시험은 {@code false} 로 끈다 — 컨텍스트마다 파티션이 생기면 위치 행이 어느 파티션에 들어가는지 단언이 갈린다. */
    @Value("${app.location.partition.on-startup:true}")
    private boolean onStartup;

    /**
     * 매일 오늘부터 {@link #LOOK_AHEAD_DAYS} 일 뒤까지 없는 파티션을 만든다 — 이미 있으면 건너뛰어 멱등이다. cron 을 설정으로
     * 받는 이유는 시험에서 배경 실행을 끄기 위함이다({@code build.gradle} 이 {@code -} 를 넣는다).
     */
    @Scheduled(cron = "${app.location.partition.cron:0 10 0 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "run-position-partition", lockAtMostFor = "PT10M")
    public void ensureAhead() {
        int created = partitionManager.ensureAhead(LocalDate.now(clock), LOOK_AHEAD_DAYS);
        if (created > 0) {
            log.info("위치 이력 파티션 미리 만들기 — {}개 생성", created);
        }
    }

    /** 기동 직후 한 번 — 서버가 자정에 내려가 있었어도 오늘 이후 파티션이 곧바로 갖춰진다. 실패해도 기동을 막지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureOnStartup() {
        if (!onStartup) {
            return;
        }
        try {
            ensureAhead();
        } catch (RuntimeException e) {
            log.warn("기동 직후 위치 이력 파티션 미리 만들기 실패 — 기본 파티션이 받고 매일 실행이 다시 돈다", e);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
    }
}
