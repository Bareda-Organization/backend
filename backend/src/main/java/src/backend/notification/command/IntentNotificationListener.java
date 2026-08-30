package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.event.IntentChangedEvent;

/**
 * 탑승 의사 변경(즉시 반영·승인 대기)을 {@code intent_changed}·{@code approval_requested} 알림으로
 * 옮기는 구독자(API_SPEC §9.7 — 두 알림 모두 수신자는 관계자).
 *
 * <p>두 이벤트를 한 클래스에 둔다 — {@code RunRouteConfirmedNotificationListener} 가 지키는
 * "이벤트 1개당 리스너 1개" 관례에서 벗어나는 판단이다. 갈라 두지 않는 이유는 수신자 조회
 * ({@link AcademyStaffRepository#findActiveAccountsByAcademyId})와 학원 단위 관계자 전원이라는
 * 대상 규칙이 두 이벤트에서 완전히 같기 때문이다 — 클래스를 둘로 쪼개면 그 공통 로직이 그대로
 * 복제되거나, 셋째 클래스로 다시 뽑아야 해 오히려 간접이 하나 늘어난다.
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@code RunRouteConfirmedNotificationListener} 와 같다 — 두 이벤트 모두 커맨드 서비스의 열린
 * 트랜잭션 안에서 발행되므로, 이 리스너도 그 트랜잭션이 아직 열려 있는 동안 실행된다.
 */
@Component
@RequiredArgsConstructor
public class IntentNotificationListener {

    private static final String INTENT_CHANGED_DEDUP_KEY_FORMAT = "intent_changed:%d:%d:%s";

    private static final String APPROVAL_REQUESTED_DEDUP_KEY_FORMAT = "approval_requested:%d:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<IntentChangedEvent> intentChangedComposer;

    private final NotificationComposer<ApprovalRequestedEvent> approvalRequestedComposer;

    /** ①·③구간 즉시 반영 결과를 그 학원 재직 관계자 전원에게 적재한다. */
    @EventListener
    public void appendIntentChanged(IntentChangedEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }

        NotificationMessage message = intentChangedComposer.compose(event);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.INTENT_CHANGED, message.title(),
                    message.body(),
                    INTENT_CHANGED_DEDUP_KEY_FORMAT.formatted(event.runId(), recipient.accountId(),
                            event.changedAt())));
        }
    }

    /** ②구간 승인 대기 접수를 그 학원 재직 관계자 전원에게 적재한다. */
    @EventListener
    public void appendApprovalRequested(ApprovalRequestedEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }

        NotificationMessage message = approvalRequestedComposer.compose(event);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.APPROVAL_REQUESTED, message.title(),
                    message.body(),
                    APPROVAL_REQUESTED_DEDUP_KEY_FORMAT.formatted(event.runId(), recipient.accountId(),
                            event.requestedAt())));
        }
    }
}
