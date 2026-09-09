package src.backend.observability.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.observability.metrics.RunUnconfirmedMetrics;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * {@code schoolbus.run.unconfirmed} 게이지(관측 목표 8, TECH_DECISIONS §13.1·§13.4)를 갱신한다 —
 * 확정 배치({@code RunConfirmationScheduler})와는 별도로 도는 순수 조회 전용 폴링이다.
 *
 * <p>문턱을 {@code §13.4} 알럿 조건과 맞춰 {@code confirm_at + 5분} 이 지난 idle 회차로 잡는다 —
 * 확정 배치 자체의 조회 문턱({@code confirm_at <= now})보다 5분 여유를 더 준 것은, 이 게이지가
 * "배치가 늦다" 가 아니라 "배치가 늦어 경보를 울릴 만큼 늦다" 를 재려는 목적이기 때문이다.
 *
 * <p>{@code @SchedulerLock} — 인스턴스마다 같은 집계를 반복 계산하는 것을 막을 뿐, 이중 실행이
 * 데이터를 틀리게 만들지는 않는다(쓰기 없는 순수 조회). 그래도 다른 스케줄러와 같은 관례를 따라
 * 붙인다 — {@code SchedulerLockConventionTest} 의 고정 면제 목록에는 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RunUnconfirmedGaugeScheduler {

    private final RunRepository runRepository;

    private final RunUnconfirmedMetrics metrics;

    private final Clock clock;

    /**
     * 판정 시각을 5분 넘긴 idle 회차 수를 세어 게이지에 반영한다.
     *
     * <p>폴링 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 미루기 위함</b>이다 —
     * {@code ChangeRequestAutoRejectionScheduler} 와 같은 형태.
     */
    @Scheduled(fixedDelayString = "${app.observability.run-unconfirmed.poll-interval-ms:30000}",
            initialDelayString = "${app.observability.run-unconfirmed.initial-delay-ms:0}")
    @SchedulerLock(name = "run-unconfirmed-gauge", lockAtMostFor = "PT2M")
    public void refresh() {
        OffsetDateTime threshold = OffsetDateTime.now(clock).minusMinutes(5);
        long count = runRepository.countByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNull(
                RunStatus.IDLE, threshold);
        metrics.update(count);
    }
}
