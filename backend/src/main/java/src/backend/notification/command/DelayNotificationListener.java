package src.backend.notification.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.DelaySubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.DelayNoticeRecipient;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * {@link DelayRequestedEvent} 를 구독해 관계자·학부모·학생 3집합을 아웃박스에 적재한다(NTF-06,
 * API_SPEC §4.9, F3 S1 라운드 1 — 반려 🔴-A 해소). {@link RunStartedNotificationListener} 와 같은
 * 이유로 {@code @EventListener}(트랜잭션 후가 아니라 <b>같은 트랜잭션 안</b>)를 쓴다 — 지연 신고
 * 커맨드 서비스가 이미 연 트랜잭션 안에서 이벤트가 발행되고, 그 트랜잭션이 롤백되면 이 리스너가
 * 적재한 아웃박스 행도 함께 롤백돼야 한다(아웃박스는 "실제 발신이 성사됐을 때만 남아야" 하는
 * 기록이다 — 실 푸시 송신은 별도 디스패처가 이 테이블을 읽어 비동기로 한다).
 *
 * <p>수신자 조회를 다시 하지 않는다 — {@link RunStartedNotificationListener} 는 {@code runId}·
 * {@code academyId} 만 받아 스스로 재조회하지만, 이 리스너는 커맨드 서비스가 응답 불리언을 위해
 * <b>이미 계산해 둔</b> 3집합을 이벤트에서 그대로 받는다({@link DelayRequestedEvent} 자바독 참고) —
 * 같은 조회를 두 번 하지 않기 위한 의도적인 차이다.
 */
@Component
@RequiredArgsConstructor
public class DelayNotificationListener {

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<DelaySubject> delayComposer;

    private final StudentRepository studentRepository;

    @EventListener
    public void appendDelayNotice(DelayRequestedEvent event) {
        NotificationMessage message = delayComposer
                .compose(new DelaySubject(event.reason(), event.minutes(), event.message()));
        append(event, event.staffRecipients(), Role.STAFF, message);
        append(event, event.guardianRecipients(), Role.PARENT, message);
        append(event, event.studentRecipients(), Role.STUDENT, message);
    }

    private void append(DelayRequestedEvent event, List<DelayNoticeRecipient> recipients, Role role,
            NotificationMessage message) {
        for (DelayNoticeRecipient recipient : recipients) {
            Student student = studentOf(role, recipient);
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), role, NotificationType.DELAY, message.title(), message.body(),
                    dedupKey(event.runId(), recipient.dedupTargetId(), event.sentAt()),
                    student != null ? student.getId() : null, student != null ? student.getName() : null, null));
        }
    }

    /**
     * 대상 자녀를 역할별로 가른다 — 관계자는 회차 전체(다수 학생)를 알리므로 특정 자녀가 없다
     * ({@code delay} 관계자 수신은 "회차 전체" 이지 개별 학생이 아니다, API_SPEC §9.7).
     * 학부모는 {@link DelayNoticeRecipient#dedupTargetId} 가 이미 studentId 다(자바독). 학생은 본인이
     * 대상이라 {@code accountId} 로 자신의 학생 행을 역조회한다.
     */
    private Student studentOf(Role role, DelayNoticeRecipient recipient) {
        return switch (role) {
            case PARENT -> studentRepository.findById(recipient.dedupTargetId()).orElse(null);
            case STUDENT -> studentRepository.findByAccountId(recipient.accountId()).orElse(null);
            default -> null;
        };
    }

    private String dedupKey(Long runId, Long targetId, OffsetDateTime sentAt) {
        return "delay:%d:%d:%s".formatted(runId, targetId, sentAt);
    }
}
