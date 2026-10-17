package src.backend.location.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.global.location.RunRidersChangedEvent;
import src.backend.location.infrastructure.RunBroadcastCache;
import src.backend.run.event.RunCanceledEvent;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.event.StopArrivedEvent;

/**
 * 위치 방송 캐시({@link RunBroadcastCache})를 도착 · 결석 · 운행 전환이 <b>커밋된 뒤</b> 비운다(RT-5, Ruling 899 K). 발행이 트랜잭션 밖이어도
 * 돈다({@code fallbackExecution}).
 */
@Component
@RequiredArgsConstructor
public class RunBroadcastCacheEvictionListener {

    private final RunBroadcastCache cache;

    /** 정류장 도착 — 현재 정차지와 다음 도착 예정 시각이 바뀐다(탑승 학생 목록은 그대로). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStopArrived(StopArrivedEvent event) {
        cache.evictStops(event.runId());
    }

    /** 운행 시작. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunStarted(RunStartedEvent event) {
        cache.evict(event.runId());
    }

    /** 운행 종료. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunEnded(RunEndedEvent event) {
        cache.evict(event.runId());
    }

    /** 회차 취소. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunCanceled(RunCanceledEvent event) {
        cache.evict(event.runId());
    }

    /** 노선 확정(새 판본) — 정차 목록 자체가 바뀐다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRouteConfirmed(RunRouteConfirmedEvent event) {
        cache.evict(event.runId());
    }

    /** 탑승 학생 목록 변경 — 결석 전이 · 탑승자 최초 생성. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRidersChanged(RunRidersChangedEvent event) {
        cache.evictStudents(event.runId());
    }
}
