package src.backend.observability.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.observability.metrics.StaleMovingRunMetrics;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.repository.RunRepository;

/**
 * {@code schoolbus.run.moving.stale} 게이지를 갱신한다(R46-KFIXBE K-1, Ruling 702) — 근접 판정·위치 유실 집계·노선 잠금이
 * 더는 집지 않는 <b>끝나지 않은 이동 중 회차</b>({@link MovingRunWindowPolicy} 범위 밖)를 사람에게 드러내는 유일한 신호다.
 * 자동으로 종료하지 않는다 — 잔류 인원이 남은 회차를 시스템이 임의로 끝내는 것은 사양(C-15) 변경이라 사람이 처리한다.
 *
 * <p>쓰기 없는 순수 조회라 {@code @SchedulerLock} 은 같은 집계를 인스턴스마다 반복하지 않게 하는 용도뿐이다
 * ({@code RunUnconfirmedGaugeScheduler} 와 같은 관례). 값은 날짜가 바뀌거나 사람이 회차를 처리할 때만 달라져 주기를 10분으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class StaleMovingRunGaugeScheduler {

    private final RunRepository runRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    private final StaleMovingRunMetrics metrics;

    /** 폴링 주기를 설정으로 받는 이유는 테스트에서 배경 실행을 미루기 위함이다({@code RunUnconfirmedGaugeScheduler} 와 같다). */
    @Scheduled(fixedDelayString = "${app.observability.stale-moving-run.poll-interval-ms:600000}",
            initialDelayString = "${app.observability.stale-moving-run.initial-delay-ms:0}")
    @SchedulerLock(name = "stale-moving-run-gauge", lockAtMostFor = "PT2M")
    public void refresh() {
        metrics.update(runRepository.countStaleMoving(movingRunWindowPolicy.earliestServiceDate()));
    }
}
