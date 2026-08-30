package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.RunRouteConfirmedEvent;

/**
 * 회차 확정을 {@code route_changed} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자는 기사·동승자).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 다 — 이유는
 * {@link SignupDecidedNotificationListener} 와 같다(TECH_DECISIONS §7.2, 적재는 확정과 같은
 * 트랜잭션 안). {@link RunRouteConfirmedEvent} 는 {@code RunConfirmationPersistence.persist} 의
 * 마지막 줄에서 발행되므로, 이 리스너는 그 메서드의 트랜잭션이 아직 열려 있는 동안 실행된다 —
 * 그 트랜잭션이 롤백되면 이 리스너가 적재한 행도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class RunRouteConfirmedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}} 을 그대로 따른다.
     *
     * <p>대상 자리에 {@code accountId} 가 아니라 {@code managerId} 를 쓴다 — {@code accountId} 는
     * 배치 시점에 {@code NULL} 일 수 있고({@code Manager.accountId} javadoc) 이후 연결로 바뀔 수도
     * 있어, 키의 안정된 축이 못 된다. {@code managerId} 는 그 회차의 그 자리에 배치된 시점에 고정이다.
     *
     * <p>네 번째 자리를 요청 식별자가 아니라 <b>판정(확정) 시각</b>으로 두는 이유는
     * {@link SignupDecidedNotificationListener} 와 같다 — Phase 8 의 재확정(②구간 승인이
     * {@code route_version} 을 +1 하는 경로)이 같은 회차를 다시 확정할 때 새 확정 시각이 붙어야
     * 같은 키로 조용히 차단되지 않는다.
     */
    private static final String DEDUP_KEY_FORMAT = "route_changed:%d:%d:%s";

    private final AssignmentRepository assignmentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunRouteConfirmedEvent> routeChangedComposer;

    /**
     * 그 회차에 배치된 기사·동승자 각각 앞으로 발송 대기 행을 적재한다.
     *
     * <p>배정이 없으면(기사가 아직 안 붙은 회차) 아무 것도 하지 않는다 — 오류가 아니다. 계정이
     * 연결되지 않은 매니저({@code accountId == null})도 건너뛴다 — {@code notification_log.
     * recipient_account_id} 가 {@code NOT NULL} 이라 그 행을 만들 수단이 없다.
     */
    @EventListener
    public void appendRouteChanged(RunRouteConfirmedEvent event) {
        List<AssignedManagerAccountView> assignees = assignmentRepository
                .findAssignedManagerAccounts(event.academyId(), event.runId());
        if (assignees.isEmpty()) {
            return;
        }

        NotificationMessage message = routeChangedComposer.compose(event);
        for (AssignedManagerAccountView assignee : assignees) {
            if (assignee.accountId() == null) {
                continue;
            }
            notificationOutbox.append(new NotificationDraft(event.academyId(), assignee.accountId(),
                    assignee.name(), roleOf(assignee.role()), NotificationType.ROUTE_CHANGED,
                    message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), assignee.managerId(), event.confirmedAt())));
        }
    }

    /** 운행 배치 역할({@code manager.role})을 계정 역할({@code account.role})로 옮긴다 — 값 이름이 같아 직접 대응한다. */
    private static Role roleOf(ManagerRole managerRole) {
        return managerRole == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
    }
}
