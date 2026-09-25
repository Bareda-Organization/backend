package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.notification.domain.spec.ChangeAutoRejectedSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.request.event.ChangeRequestAutoRejectedEvent;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 변경 요청 자동 거절을 {@code change_decided} 알림으로 옮기는 구독자(API_SPEC §1.6·§9.7 — 수신자는
 * 신청 학부모 본인).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 다 — 이유는
 * {@link SignupDecidedNotificationListener} 와 같다(적재는 자동 거절과 같은 트랜잭션 안).
 * {@link ChangeRequestAutoRejectedEvent} 는 {@code ChangeRequestAutoRejectionPersistence.autoRejectOne}
 * 의 마지막 줄에서 발행되므로, 이 리스너는 그 메서드의 트랜잭션이 아직 열려 있는 동안 실행된다 —
 * 그 트랜잭션이 롤백되면(예: 이 리스너 자체가 던지는 예외) 자동 거절 갱신도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class ChangeRequestAutoRejectedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}} 을 그대로 따른다.
     *
     * <p>대상 자리에 {@code changeRequestId} 를 쓴다 — 알림은 요청 한 건의 결과라, 학생으로 잡으면 같은
     * 학생의 대기 건 둘이 한 판정 시각에 함께 자동 거절될 때 같은 키가 되어 운행 시작 트랜잭션 전체가
     * 롤백됐다(BR-067).
     */
    private static final String DEDUP_KEY_FORMAT = "change_decided:%d:%d:%s";

    private final AccountRepository accountRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<ChangeAutoRejectedSubject> changeAutoRejectedComposer;

    private final StudentRepository studentRepository;

    /**
     * 신청 학부모 앞으로 발송 대기 행을 적재한다.
     *
     * <p>계정이 조회되지 않으면(탈퇴 등) 아무 것도 하지 않는다 — {@code notification_log.
     * recipient_account_id} 가 {@code NOT NULL} 이라 그 행을 만들 수단이 없다
     * ({@link RunRouteConfirmedNotificationListener} 가 배정 없는 매니저를 건너뛰는 것과 같은 근거).
     */
    @EventListener
    public void appendChangeDecided(ChangeRequestAutoRejectedEvent event) {
        Account requester = accountRepository.findById(event.requestedBy()).orElse(null);
        if (requester == null) {
            return;
        }

        String studentName = studentRepository.findById(event.studentId()).map(Student::getName).orElse(null);
        NotificationMessage message = changeAutoRejectedComposer.compose(new ChangeAutoRejectedSubject(studentName));
        notificationOutbox.append(new NotificationDraft(event.academyId(), requester.getId(), requester.getName(),
                requester.getRole(), NotificationType.CHANGE_DECIDED, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.changeRequestId(), event.decidedAt()),
                event.studentId(), studentName, null));
    }
}
