package src.backend.notification.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 * 발행되지 않아야 한다" 를 구독자 몫으로 정했다. 커밋 뒤라 원 트랜잭션이 없으므로 적재는 새 트랜잭션에서 한다
 * ({@link NotificationOutbox#append} 는 {@code MANDATORY}). 이 적재가 실패하면 배치는 그대로이고 통지만 빠진다.
 */
@Component
@RequiredArgsConstructor
public class AssignmentChangedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 매니저 id({@link RunRouteConfirmedNotificationListener} 와 같은 근거). */
    private static final String DEDUP_KEY_FORMAT = "assignment_changed:%d:manager:%d:%s";

    private final ManagerRepository managerRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<AssignmentChangedEvent> assignmentChangedComposer;

    /** 새로 배치된 매니저의 계정에 적재한다 — 계정이 연결되지 않았거나 삭제된 매니저는 받을 계정이 없어 건너뛴다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendAssignmentChanged(AssignmentChangedEvent event) {
        managerRepository.findByIdAndAcademyIdAndDeletedAtIsNull(event.managerId(), event.academyId())
                .filter(manager -> manager.getAccountId() != null)
                .ifPresent(manager -> {
                    NotificationMessage message = assignmentChangedComposer.compose(event);
                    notificationOutbox.append(new NotificationDraft(event.academyId(), manager.getAccountId(),
                            manager.getName(), roleOf(event.role()), NotificationType.ASSIGNMENT_CHANGED,
                            message.title(), message.body(),
                            DEDUP_KEY_FORMAT.formatted(event.runId(), event.managerId(), event.changedAt())));
                });
    }

    private static Role roleOf(ManagerRole managerRole) {
        return managerRole == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
    }
}
