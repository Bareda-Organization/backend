package src.backend.notification.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.manager.repository.ManagerRepository;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 배치 변경을 {@code assignment_changed} 알림으로 옮기는 구독자(API_SPEC §9.7 "당일 배치 변경 → 해당 매니저", MGR-05
 * · Ruling 330, BR-110).
 *
 * <p>커밋 후에 적재한다 — 이벤트 계약({@link AssignmentChangedEvent} javadoc)이 "배치를 바꾼 트랜잭션이 롤백되면
 * 발행되지 않아야 한다" 를 구독자 몫으로 정했다. 적재는 새 트랜잭션에서 한다({@link NotificationOutbox#append} 는
 * {@code MANDATORY}). 이 적재가 실패하면 배치는 그대로이고 통지만 빠진다.
 *
 * <p><b>새 트랜잭션은 알림 실행기에서 연다</b>(R46 T-4) — 커밋 뒤 콜백은 바깥 트랜잭션의 연결을 아직 쥔 채 돈다. 그 자리에서
 * 새 트랜잭션을 열면 한 요청이 연결 2개를 동시에 쥐고, 풀이 찬 때 서로 풀리지 않는 대기가 된다. 그래서 콜백은 제출만 하고
 * 돌아가 바깥 연결을 먼저 반납한다({@code NotificationDispatchListener} 와 같은 모양).
 */
@Component
@RequiredArgsConstructor
public class AssignmentChangedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 매니저 id({@link RunRouteConfirmedNotificationListener} 와 같은 근거). */
    private static final String DEDUP_KEY_FORMAT = "assignment_changed:%d:manager:%d:%s";

    private static final Logger log = LoggerFactory.getLogger(AssignmentChangedNotificationListener.class);

    private final ManagerRepository managerRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<AssignmentChangedEvent> assignmentChangedComposer;

    private final TransactionTemplate transactionTemplate;

    @Qualifier("notificationDispatchExecutor")
    private final ThreadPoolTaskExecutor notificationDispatchExecutor;

    /**
     * 커밋된 배치 변경을 알림 실행기에 넘기고 돌아간다. 제출이 거절되거나(대기열 가득) 적재가 실패해도 이미 커밋된 배치를
     * 되돌릴 수 없어 삼키고 통지만 빠진다(Ruling 330 · BR-110 — 알림 로그 화면에서 확인).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void appendAfterCommit(AssignmentChangedEvent event) {
        try {
            notificationDispatchExecutor.execute(() -> appendSafely(event));
        } catch (TaskRejectedException e) {
            log.warn("[outbox] 배치 변경 통지 대기열이 가득 차 통지를 건너뛴다. runId={} managerId={}", event.runId(),
                    event.managerId());
        }
    }

    private void appendSafely(AssignmentChangedEvent event) {
        try {
            appendAssignmentChanged(event);
        } catch (RuntimeException e) {
            log.warn("[outbox] 배치 변경 통지 적재가 실패해 통지만 건너뛴다(배치는 그대로). runId={} managerId={}",
                    event.runId(), event.managerId(), e);
        }
    }

    /**
     * 새로 배치된 매니저의 계정에 새 트랜잭션으로 적재한다 — 계정이 연결되지 않았거나 삭제된 매니저는 받을 계정이 없어 건너뛴다.
     * 열린 트랜잭션이 없는 스레드(알림 실행기)에서 부른다.
     */
    public void appendAssignmentChanged(AssignmentChangedEvent event) {
        transactionTemplate.executeWithoutResult(status -> managerRepository
                .findByIdAndAcademyIdAndDeletedAtIsNull(event.managerId(), event.academyId())
                .filter(manager -> manager.getAccountId() != null)
                .ifPresent(manager -> {
                    NotificationMessage message = assignmentChangedComposer.compose(event);
                    notificationOutbox.append(new NotificationDraft(event.academyId(), manager.getAccountId(),
                            manager.getName(), roleOf(event.role()), NotificationType.ASSIGNMENT_CHANGED,
                            message.title(), message.body(),
                            DEDUP_KEY_FORMAT.formatted(event.runId(), event.managerId(), event.changedAt()),
                            null, null, null, event.runId()));
                }));
    }

    private static Role roleOf(ManagerRole managerRole) {
        return managerRole == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
    }
}
