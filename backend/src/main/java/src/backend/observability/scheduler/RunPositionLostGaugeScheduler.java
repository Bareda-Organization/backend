package src.backend.observability.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.monitoring.query.RunLiveStateResolver;
import src.backend.observability.metrics.RunPositionLostMetrics;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * 위치가 2분 넘게 안 들어온 운행 중 회차 수를 주기적으로 세어 {@link RunPositionLostMetrics} 에 싣는다
 * ({@code TECH_DECISIONS §13.4} "운행 중 회차의 위치가 2분 이상 미수신 — 경고", BR-064).
 *
 * <p>유실 판정은 관제 화면과 같은 {@link RunLiveStateResolver} 를 쓴다 — 기준이 갈리면 알럿과 화면이 서로 다른 버스를
 * 가리킨다. 출발 직후 첫 위치가 오기 전인 회차는 세지 않는다(출발 후 2분이 지나야 "미수신" 이다).
 */
@Component
@RequiredArgsConstructor
public class RunPositionLostGaugeScheduler {

    /** 한 번에 보는 운행 중 회차 상한 — 목표 규모(학원 10곳)의 동시 운행 수보다 넉넉하다. */
    private static final int BATCH_SIZE = 500;

    private final RunRepository runRepository;

    private final RunLiveStateResolver runLiveStateResolver;

    private final RunPositionLostMetrics metrics;

    private final Clock clock;

    /** 폴링 주기를 설정으로 받는 이유는 테스트에서 배경 실행을 미루기 위함이다({@code RunUnconfirmedGaugeScheduler} 와 같다). */
    @Scheduled(fixedDelayString = "${app.observability.run-position-lost.poll-interval-ms:30000}",
            initialDelayString = "${app.observability.run-position-lost.initial-delay-ms:0}")
    @SchedulerLock(name = "run-position-lost-gauge", lockAtMostFor = "PT2M")
    public void refresh() {
        OffsetDateTime startedBefore = OffsetDateTime.now(clock).minus(StudentBusPositionQueryService.STALE_THRESHOLD);
        // 운행 중 회차를 id 순으로 끝까지 이어 읽는다 — 첫 묶음만 세면 그 뒤 회차의 유실이 지표에 안 잡힌다
        // (근접 판정 BR-011 과 같은 형태).
        long lost = 0;
        long afterId = 0L;
        while (true) {
            List<Run> moving = runRepository.findByStatusAndCanceledAtIsNullAndIdGreaterThanOrderByIdAsc(
                    RunStatus.MOVING, afterId, PageRequest.of(0, BATCH_SIZE));
            lost += moving.stream()
                    .filter(run -> run.getStartedAt() != null && !run.getStartedAt().isAfter(startedBefore))
                    .filter(run -> runLiveStateResolver.resolve(run, List.of()).stale())
                    .count();
            if (moving.size() < BATCH_SIZE) {
                break;
            }
            afterId = moving.get(moving.size() - 1).getId();
        }
        metrics.update(lost);
    }
}
