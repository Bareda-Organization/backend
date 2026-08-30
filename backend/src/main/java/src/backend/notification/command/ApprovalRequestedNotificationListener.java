package src.backend.notification.command;

import java.util.List;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.request.event.ApprovalRequestedEvent;

/**
 * ②구간 변경 신청 접수를 {@code approval_requested} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자는
 * 학원 관계자).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 다 —
 * {@link RunRouteConfirmedNotificationListener} 와 같은 근거(TECH_DECISIONS §7.2, 적재는 접수와
 * 같은 트랜잭션 안). {@link ApprovalRequestedEvent} 는 {@code ChangeRequestStore} 의 저장 메서드
 * 마지막에서 발행되므로, 이 리스너는 그 트랜잭션이 아직 열려 있는 동안 실행된다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalRequestedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}} 을 그대로 따른다.
     *
     * <p>대상 자리에 재직 관계자의 {@code academy_staff.id} 를 쓴다 — {@code uk_academy_staff_academy_active}
     * 가 학원당 재직 1건을 강제하므로 그 값이 안정된 축이다. 네 번째 자리는 이 변경 신청의
     * {@code requested_at} 이다 — 같은 회차에 같은 관계자 앞으로 신청이 여러 번 접수돼도(②구간
     * 한도 안에서 다른 학생이 신청) 접수 시각이 갈려 같은 키로 조용히 차단되지 않는다.
     */
    private static final String DEDUP_KEY_FORMAT = "approval_requested:%d:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<ApprovalRequestedEvent> approvalRequestedComposer;

    /**
     * 그 학원의 재직 관계자 앞으로 발송 대기 행을 적재한다.
     *
     * <p>재직 관계자가 없으면(아직 아무도 관계자로 승인되지 않은 학원) 아무 것도 하지 않는다 — 오류가
     * 아니다. {@link RunRouteConfirmedNotificationListener} 가 배치 없는 회차를 건너뛰는 것과 같은 형태다.
     */
    @EventListener
    public void appendApprovalRequested(ApprovalRequestedEvent event) {
        Optional<AcademyStaff> staff = academyStaffRepository.findByAcademyIdAndStatus(event.academyId(),
                StaffStatus.ACTIVE);
        if (staff.isEmpty()) {
            return;
        }
        List<Account> accounts = accountRepository.findAllByAcademyIdAndIdIn(event.academyId(),
                List.of(staff.get().getAccountId()));
        if (accounts.isEmpty()) {
            return;
        }
        Account recipient = accounts.get(0);

        NotificationMessage message = approvalRequestedComposer.compose(event);
        notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.getId(), recipient.getName(),
                Role.STAFF, NotificationType.APPROVAL_REQUESTED, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), staff.get().getId(), event.requestedAt())));
    }
}
