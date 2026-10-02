package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
 * <p>{@code @EventListener} — 배치를 바꾼 트랜잭션 안에서 적재한다({@link RunRouteConfirmedNotificationListener} 등 다른 적재
 * 리스너와 같은 관례, BR-320). 그 트랜잭션이 롤백되면 이 행도 함께 사라져 롤백된 배치는 통지하지 않고, 커밋되면 행이 이미
 * 있어 아웃박스 워커가 주워 갈 수 있다. 커밋 뒤 메모리 큐에 넘기면 제출 거절 · 적재 실패 · 종료 때 행이 생기기 전에 사라져
 * 재시도·지표·알림 로그에 흔적이 남지 않는다 — 동승자 자동 배정에서는 이 통지가 배치된 동승자에게 가는 유일한 알림이다.
 * 적재가 실패하면 배치를 바꾼 트랜잭션도 되돌려진다.
 */
@Component
@RequiredArgsConstructor
public class AssignmentChangedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 매니저 id({@link RunRouteConfirmedNotificationListener} 와 같은 근거). */
    private static final String DEDUP_KEY_FORMAT = "assignment_changed:%d:manager:%d:%s";

    private final ManagerRepository managerRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<AssignmentChangedEvent> assignmentChangedComposer;

    /**
     * 새로 배치된 매니저의 계정에 적재한다 — 계정이 연결되지 않았거나 삭제된 매니저는 받을 계정이 없어 건너뛴다.
     * 배치를 바꾼 트랜잭션 안에서 부른다({@link NotificationOutbox#append} 는 {@code MANDATORY}).
     */
    @EventListener
    public void appendAssignmentChanged(AssignmentChangedEvent event) {
        managerRepository.findByIdAndAcademyIdAndDeletedAtIsNull(event.managerId(), event.academyId())
                .filter(manager -> manager.getAccountId() != null)
                .ifPresent(manager -> {
                    NotificationMessage message = assignmentChangedComposer.compose(event);
                    notificationOutbox.append(new NotificationDraft(event.academyId(), manager.getAccountId(),
                            manager.getName(), roleOf(event.role()), NotificationType.ASSIGNMENT_CHANGED,
                            message.title(), message.body(),
                            DEDUP_KEY_FORMAT.formatted(event.runId(), event.managerId(), event.changedAt()),
                            null, null, null, event.runId()));
                });
    }

    private static Role roleOf(ManagerRole managerRole) {
        return managerRole == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
    }
}
