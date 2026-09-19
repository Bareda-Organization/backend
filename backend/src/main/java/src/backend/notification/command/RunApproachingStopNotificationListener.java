package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.notification.domain.impl.RunApproachingStopSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 근접 알림(NTF-04, API_SPEC §9.7 {@code arrive})을 옮기는 구독자 — {@link RunApproachingStopEvent}
 * 가 학생 1명당 1건이라 이 리스너도 1건마다 1번 실행되고, 그 호출마다 학부모·학생 합쳐 많아야 2행을
 * 적재한다.
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@code RunRouteConfirmedNotificationListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class RunApproachingStopNotificationListener {

    /**
     * {@code dedup_key} 형태 — 대상 자리는 runId·stopId·studentId·수신자 구분(parent/student) 이다.
     * <b>시각을 넣지 않는다</b> — 최초 1회 발송의 실제 강제자는 {@code RunStopRepository
     * #claimProximityNotice} 의 조건부 UPDATE(목표 15)이고, 이 키는 그 선점이 새더라도 같은 정차지·
     * 같은 학생·같은 수신자에게 두 번 적재되는 것을 막는 마지막 방어선이다. 시각을 넣으면 재판정
     * 시각마다 값이 달라져 이 방어선이 통과만 하는 장식이 된다(Phase 4 사고 형태 — 소비 시점 시계로
     * dedup_key 를 구성해 매번 새 값이 나온 것과 같은 실수).
     *
     * <p>마지막 자리에 수신자 구분을 더한 것(목표 4) — 학부모·학생 두 행이 같은 runId·stopId·
     * studentId 를 공유해, 구분이 없으면 두 번째 적재가 UNIQUE(dedup_key) 위반으로 막힌다.
     */
    private static final String DEDUP_KEY_FORMAT = "approaching:%d:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentRepository studentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunApproachingStopSubject> runApproachingStopComposer;

    @EventListener
    public void appendRunApproachingStop(RunApproachingStopEvent event) {
        // 문구·student_name 컬럼이 같은 값을 쓰도록(목표 3) 한 번만 조회해 두 수신자에 그대로 재사용한다.
        String studentName = studentRepository.findById(event.studentId()).map(Student::getName).orElse(null);
        NotificationMessage message = runApproachingStopComposer.compose(new RunApproachingStopSubject(studentName));

        appendToGuardian(event, message, studentName);
        appendToStudent(event, message, studentName);
    }

    /** 그 학생의 <b>첫 보호자</b> 1명에게만 적재한다. 연결된 보호자가 없으면 아무 것도 하지 않는다. */
    private void appendToGuardian(RunApproachingStopEvent event, NotificationMessage message, String studentName) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), List.of(event.studentId()));
        if (guardians.isEmpty()) {
            return;
        }
        GuardianAccountRecipient firstGuardian = guardians.get(0);
        notificationOutbox.append(new NotificationDraft(event.academyId(), firstGuardian.getAccountId(),
                firstGuardian.getName(), Role.PARENT, NotificationType.ARRIVE, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.stopId(), event.studentId(), "parent"),
                event.studentId(), studentName, null));
    }

    /**
     * 학생 본인 — API_SPEC §9.7 {@code arrive} 수신자가 "학부모·학생" 이라 학생 계정에도 적재한다
     * (목표 4, R13 이 발견). 계정이 연결된 학생만 대상이다(로그인이 없는 학생은 받을 계정 자체가 없다).
     */
    private void appendToStudent(RunApproachingStopEvent event, NotificationMessage message, String studentName) {
        List<Student> students = studentRepository
                .findAllByIdInAndAcademyIdAndAccountIdIsNotNull(List.of(event.studentId()), event.academyId());
        if (students.isEmpty()) {
            return;
        }
        Student student = students.get(0);
        notificationOutbox.append(new NotificationDraft(event.academyId(), student.getAccountId(),
                student.getName(), Role.STUDENT, NotificationType.ARRIVE, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.stopId(), event.studentId(), "student"),
                event.studentId(), studentName, null));
    }
}
