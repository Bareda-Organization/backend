package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.request.event.ChangeRequestDecidedEvent;

/**
 * 관리자의 ②구간 승인·거절을 {@code change_decided} 알림으로 옮기는 구독자(API_SPEC §5.6·§9.7 —
 * 수신자는 신청 학부모 본인).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 다 — 이유는
 * {@link SignupDecidedNotificationListener} 와 같다. {@link ChangeRequestDecidedEvent} 는
 * {@code ChangeRequestDecisionService.decide} 의 아직 열린 트랜잭션 안에서 발행되므로, 그 트랜잭션이
 * 실패로 롤백되면(목표 7) 이 적재도 함께 취소된다 — 승인이 실패했는데 통지만 나가는 경우가 생기지
 * 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChangeRequestDecidedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}} 을 그대로 따른다
     * ({@link ChangeRequestAutoRejectedNotificationListener} 와 같은 근거로 대상 자리는
     * {@code studentId} 다).
     */
    private static final String DEDUP_KEY_FORMAT = "change_decided:%d:%d:%s";

    private final AccountRepository accountRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<ChangeRequestDecidedEvent> changeDecidedComposer;

    /**
     * 신청 학부모 앞으로 발송 대기 행을 적재한다.
     *
     * <p>계정이 조회되지 않으면(탈퇴 등) 아무 것도 하지 않는다 — {@link ChangeRequestAutoRejectedNotificationListener}
     * 가 배정 없는 계정을 건너뛰는 것과 같은 근거({@code notification_log.recipient_account_id} NOT NULL).
     */
    @EventListener
    public void appendChangeDecided(ChangeRequestDecidedEvent event) {
        Account requester = accountRepository.findById(event.requestedBy()).orElse(null);
        if (requester == null) {
            return;
        }

        NotificationMessage message = changeDecidedComposer.compose(event);
        notificationOutbox.append(new NotificationDraft(event.academyId(), requester.getId(), requester.getName(),
                requester.getRole(), NotificationType.CHANGE_DECIDED, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.studentId(), event.decidedAt())));
    }
}
