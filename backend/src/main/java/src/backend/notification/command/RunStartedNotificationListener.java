package src.backend.notification.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.RunStartedEvent;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 운행 시작을 {@code run_started} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자 관계자·학부모·
 * 학생 3대상, 항상 발송). "항상 발송"은 {@link src.backend.notification.entity.NotificationSetting}
 * 의 on/off 대상이 도착·승차·미승차 3종뿐이라 이 알림이 애초에 그 토글 밖이라는 뜻이다 — 이
 * 리스너가 따로 걸러야 할 설정이 없다.
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@link RunRouteConfirmedNotificationListener} 와 같다 — 이 이벤트는 회차 시작 커맨드 서비스가
 * 이미 연 트랜잭션 안에서 발행되고, 그 트랜잭션이 롤백되면 이 리스너가 적재한 행도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class RunStartedNotificationListener {

    /** {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}}. 대상은 accountId. */
    private static final String DEDUP_KEY_FORMAT = "run_started:%d:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final RunRiderRepository runRiderRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentRepository studentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunStartedEvent> runStartedComposer;

    @EventListener
    public void appendRunStarted(RunStartedEvent event) {
        NotificationMessage message = runStartedComposer.compose(event);

        appendToStaff(event, message);

        List<Long> studentIds = studentIdsOf(event);
        if (studentIds.isEmpty()) {
            return;
        }
        appendToGuardians(event, message, studentIds);
        appendToStudents(event, message, studentIds);
    }

    /** 관계자 — 그 학원 재직 전원(회차의 특정 배치와 무관하다, {@link IntentNotificationListener} 와 같은 대상 규칙). */
    private void appendToStaff(RunStartedEvent event, NotificationMessage message) {
        List<AcademyStaffAccountView> staff = academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId());
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.RUN_STARTED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), recipient.accountId(), event.startedAt())));
        }
    }

    /** 지금 그 회차 명단에 오른 학생들 — 확정 배치가 쌓은 뒤 승인된 변경까지 반영된 현재 상태다. */
    private List<Long> studentIdsOf(RunStartedEvent event) {
        return runRiderRepository.findAllByRunIdAndAcademyId(event.runId(), event.academyId()).stream()
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
    }

    /**
     * 학부모 — 학생 1명에 보호자가 여럿이어도 <b>한 명(첫 보호자)</b> 에게만 보낸다.
     *
     * <p>{@code findGuardianAccountsByAcademyId} 가 정렬을 고정해 두므로, 그 순서에서 학생당
     * 처음 나오는 행만 취하는 것으로 "첫 보호자"가 결정론적이 된다.
     *
     * <p>{@code dedup_key} 의 대상 자리에 {@code accountId} 가 아니라 <b>studentId</b> 를 쓴다 —
     * {@code dedup_key} 는 테이블 전체에서 UNIQUE(ERD)라, 같은 회차에 형제자매가 함께 타 같은
     * 보호자 계정으로 귀결되면 accountId 는 두 학생에서 같아진다. studentId 는 이 반복에서 항상
     * 서로 달라 그 충돌이 나지 않는다.
     */
    private void appendToGuardians(RunStartedEvent event, NotificationMessage message, List<Long> studentIds) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), studentIds);
        Map<Long, GuardianAccountRecipient> firstGuardianPerStudent = new LinkedHashMap<>();
        for (GuardianAccountRecipient guardian : guardians) {
            firstGuardianPerStudent.putIfAbsent(guardian.getStudentId(), guardian);
        }
        for (Map.Entry<Long, GuardianAccountRecipient> entry : firstGuardianPerStudent.entrySet()) {
            GuardianAccountRecipient guardian = entry.getValue();
            notificationOutbox.append(new NotificationDraft(event.academyId(), guardian.getAccountId(),
                    guardian.getName(), Role.PARENT, NotificationType.RUN_STARTED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), entry.getKey(), event.startedAt())));
        }
    }

    /** 학생 — 계정이 연결된 학생만(로그인이 없는 학생은 알림을 받을 계정 자체가 없다). */
    private void appendToStudents(RunStartedEvent event, NotificationMessage message, List<Long> studentIds) {
        List<Student> students = studentRepository
                .findAllByIdInAndAcademyIdAndAccountIdIsNotNull(studentIds, event.academyId());
        for (Student student : students) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), student.getAccountId(),
                    student.getName(), Role.STUDENT, NotificationType.RUN_STARTED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), student.getAccountId(), event.startedAt())));
        }
    }
}
