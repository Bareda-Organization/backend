package src.backend.notification.command;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.DelaySubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.entity.Run;
import src.backend.run.dto.DelayNoticeRecipient;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.repository.RunRepository;
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

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    /** 지연 신고(NTF-06, §4.9) — 이벤트가 이미 나른 관계자·학부모·학생 3집합에 각각 적재한다. */
    @EventListener
    public void appendDelayNotice(DelayRequestedEvent event) {
        DelaySubject subject = new DelaySubject(event.reason(), event.minutes(), event.message());
        // 관계자 알림에만 호차를 채운다(목표 5, 정본 범위) — 학부모·학생 알림은 자녀 이름이 있다.
        String busNo = busNoOf(event.runId(), event.academyId());
        for (DelayNoticeRecipient recipient : event.staffRecipients()) {
            append(event, recipient, Role.STAFF, delayComposer.compose(subject), null, busNo);
        }
        // 대상 자녀는 id 목록으로 한 번씩 읽는다 — 수신자마다 다시 조회하지 않는다(BR-143).
        Map<Long, Student> byId = studentRepository.findAllByAcademyIdAndIdIn(event.academyId(),
                event.guardianRecipients().stream().map(DelayNoticeRecipient::dedupTargetId).toList())
                .stream().collect(Collectors.toMap(Student::getId, student -> student));
        for (DelayNoticeRecipient recipient : event.guardianRecipients()) {
            Student student = byId.get(recipient.dedupTargetId());
            append(event, recipient, Role.PARENT, messageFor(subject, student), student, null);
        }
        Map<Long, Student> byAccount = studentRepository.findAllByAcademyIdAndAccountIdIn(event.academyId(),
                event.studentRecipients().stream().map(DelayNoticeRecipient::accountId).toList())
                .stream().collect(Collectors.toMap(Student::getAccountId, student -> student));
        for (DelayNoticeRecipient recipient : event.studentRecipients()) {
            Student student = byAccount.get(recipient.accountId());
            append(event, recipient, Role.STUDENT, messageFor(subject, student), student, null);
        }
    }

    /** 학부모·학생 몫 문구 — 자녀 이름을 싣는다(ATT-03, BR-077). 학생 행을 못 찾으면 관계자 문구와 같다. */
    private NotificationMessage messageFor(DelaySubject subject, Student student) {
        return delayComposer.compose(student == null ? subject : subject.forStudent(student.getName()));
    }

    private void append(DelayRequestedEvent event, DelayNoticeRecipient recipient, Role role,
            NotificationMessage message, Student student, String busNo) {
        notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                recipient.name(), role, NotificationType.DELAY, message.title(), message.body(),
                dedupKey(event.runId(), targetOf(role, recipient), event.sentAt()),
                student != null ? student.getId() : null, student != null ? student.getName() : null, busNo));
    }

    /** {@link DelayRequestedEvent} 는 runId 만 나르므로 Run → Bus 를 한 번 더 거친다(목표 5). */
    private String busNoOf(Long runId, Long academyId) {
        return runRepository.findByIdAndAcademyId(runId, academyId)
                .map(Run::getBusId)
                .flatMap(busId -> busRepository.findByIdAndAcademyId(busId, academyId))
                .map(Bus::getBusNo)
                .orElse(null);
    }

    /**
     * 멱등키의 대상 자리 — 역할 접두로 계정 ID·학생 ID 공간을 가른다(BR-007). 학부모는 형제자매를 가르려고
     * 대상 학생({@link DelayNoticeRecipient#dedupTargetId})까지 붙인다.
     */
    private static String targetOf(Role role, DelayNoticeRecipient recipient) {
        return switch (role) {
            case PARENT -> "guardian:" + recipient.accountId() + ":" + recipient.dedupTargetId();
            case STUDENT -> "student:" + recipient.accountId();
            default -> "staff:" + recipient.accountId();
        };
    }

    private String dedupKey(Long runId, String target, OffsetDateTime sentAt) {
        return "delay:%d:%s:%s".formatted(runId, target, sentAt);
    }
}
