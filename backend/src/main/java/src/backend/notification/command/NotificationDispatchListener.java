package src.backend.notification.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.notification.event.NotificationAppendedEvent;

/**
 * 커밋 직후 즉시 발송을 거는 자리(TECH_DECISIONS §7.2 ①) — 워커만 있으면 폴링 주기만큼 늦어지고,
 * 즉시 발송만 있으면 커밋과 발송 사이에 앱이 죽을 때 알림이 영구 유실된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchListener.class);

    private final NotificationDispatcher notificationDispatcher;

    @Qualifier("notificationDispatchExecutor")
    private final ThreadPoolTaskExecutor notificationDispatchExecutor;

    /** 비상 종류 전용 — 일반 알림 뒤에 서지 않는다(R46 S-2). */
    @Qualifier("emergencyDispatchExecutor")
    private final ThreadPoolTaskExecutor emergencyDispatchExecutor;

    /**
     * {@code AFTER_COMMIT} 이라 상태 변경이 확정된 뒤에만 돈다 — 롤백되면 호출 자체가 부재해
     * "일어나지 않은 일" 을 통지하지 않는다.
     *
     * <p>발송은 전용 실행기에서 돈다({@code NotificationDispatchConfig}, BR-069) — 요청 스레드는 제출만 하고
     * 돌아간다. 예외를 삼키는 이유는 이 시점이 <b>커밋 이후</b>이기 때문이다. 발송 실패·제출 거절(대기열 가득)은
     * 행이 {@code pending} 으로 남아 워커가 다시 집으므로, 삼켜도 잃는 것은 부재하다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatchAfterCommit(NotificationAppendedEvent event) {
        try {
            (event.urgent() ? emergencyDispatchExecutor : notificationDispatchExecutor)
                    .execute(() -> dispatch(event.notificationId()));
        } catch (TaskRejectedException e) {
            log.warn("[outbox] 즉시 발송 대기열이 가득 차 워커 재시도로 넘긴다. id={}", event.notificationId());
        }
    }

    private void dispatch(Long notificationId) {
        try {
            notificationDispatcher.dispatch(notificationId);
        } catch (RuntimeException e) {
            log.warn("[outbox] 즉시 발송이 실패해 워커 재시도로 넘긴다. id={}", notificationId, e);
        }
    }
}
