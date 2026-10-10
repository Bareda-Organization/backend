package src.backend.notification.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RunStartedChildSubject;
import src.backend.notification.entity.NotificationType;
import src.backend.run.entity.Run;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 운행 시작을 {@code run_started} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자 관계자·학부모·
 * 학생 3대상). 발송 시점 필터는 {@link src.backend.notification.entity.NotificationSetting#isEnabledFor}
 * 가 맡는다 — {@code RUN_STARTED} 는 §3.14 "등하원(승차·하차·운행 시작) 알림" 대로 {@code boarding}
 * 토글에 귀속(NTF-05)이라, 이 리스너는 적재만 하고 켜짐·꺼짐 판정을 스스로 하지 않는다.
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@link RunRouteConfirmedNotificationListener} 와 같다 — 이 이벤트는 회차 시작 커맨드 서비스가
 * 이미 연 트랜잭션 안에서 발행되고, 그 트랜잭션이 롤백되면 이 리스너가 적재한 행도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class RunStartedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}}. 대상 자리에 역할 접두
     * ({@code staff:계정} · {@code guardian:계정:학생} · {@code student:계정})를 붙인다 — 계정 ID 와 학생
     * ID 는 서로 독립인 시퀀스라, 접두 없이 한 칸에 섞으면 값이 겹칠 때 운행 시작 전체가 롤백된다(BR-007).
     */
    private static final String DEDUP_KEY_FORMAT = "run_started:%d:%s:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final RunRiderRepository runRiderRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentRepository studentRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunStartedEvent> runStartedComposer;

    private final NotificationComposer<RunStartedChildSubject> runStartedChildComposer;

    /**
     * 운행 시작(목표 5, §9.7) — 관계자 전원 + 명단에 남은(absent 제외) 학생의 보호자·본인에게 적재한다. 수신자 전원을
     * 모아 한 번에 적재한다({@link NotificationOutbox#appendAll}, R46 T-6) — 회차 행 잠금 아래에서 문장 수가 수신자 수에
     * 비례하지 않게 한다.
     */
    @EventListener
    public void appendRunStarted(RunStartedEvent event) {
        NotificationMessage staffMessage = runStartedComposer.compose(event);

        List<NotificationDraft> drafts = new ArrayList<>(draftsForStaff(event, staffMessage));
        List<Long> studentIds = studentIdsOf(event);
        if (!studentIds.isEmpty()) {
            drafts.addAll(draftsForGuardians(event, studentIds));
            drafts.addAll(draftsForStudents(event, studentIds));
        }
        notificationOutbox.appendAll(drafts);
    }

    /**
     * 관계자 — 그 학원 재직 전원(회차의 특정 배치와 무관하다, {@link IntentNotificationListener} 와
     * 같은 대상 규칙). 호차를 채운다(목표 5) — {@link RunStartedEvent} 는 runId 만 나르므로
     * Run → Bus 를 한 번 더 거친다(수신자 전원이 같은 차량을 가리켜 순회 전에 한 번만 조회).
     */
    private List<NotificationDraft> draftsForStaff(RunStartedEvent event, NotificationMessage message) {
        List<AcademyStaffAccountView> staff = academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId());
        String busNo = busNoOf(event.runId(), event.academyId());
        return staff.stream()
                .map(recipient -> new NotificationDraft(event.academyId(), recipient.accountId(),
                        recipient.name(), Role.STAFF, NotificationType.RUN_STARTED, message.title(), message.body(),
                        DEDUP_KEY_FORMAT.formatted(event.runId(), "staff:" + recipient.accountId(),
                                event.startedAt()),
                        null, null, busNo))
                .toList();
    }

    /** 목표 5 — 학부모·학생 알림은 이미 자녀 이름이 있어 호차까지는 요구되지 않는다(정본 범위: 관계자 알림). */
    private String busNoOf(Long runId, Long academyId) {
        return runRepository.findByIdAndAcademyId(runId, academyId)
                .map(Run::getBusId)
                .flatMap(busId -> busRepository.findByIdAndAcademyId(busId, academyId))
                .map(Bus::getBusNo)
                .orElse(null);
    }

    /** 지금 그 회차 명단에 오른 학생들 — 확정 배치가 쌓은 뒤 승인된 변경까지 반영된 현재 상태다. */
    private List<Long> studentIdsOf(RunStartedEvent event) {
        return runRiderRepository.findAllByRunIdAndAcademyId(event.runId(), event.academyId()).stream()
                // absent(①구간 OFF·②구간 취소·다른 버스로 이동)는 이 버스에 타지 않는다 — 학부모 알림 부재(§9.4)
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
    }

    /**
     * 학부모 — 연결된 보호자 전원(BR-073, §9.7 수신자 "학부모" · Ruling 326 한 학생에 보호자 여럿).
     *
     * <p>{@code dedup_key} 의 대상 자리에 보호자 계정과 <b>studentId</b> 를 함께 쓴다 — 같은 회차에
     * 형제자매가 함께 타 같은 보호자 계정으로 귀결되면 계정만으로는 두 학생에서 같아진다.
     */
    private List<NotificationDraft> draftsForGuardians(RunStartedEvent event, List<Long> studentIds) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), studentIds);
        return guardians.stream().map(guardian -> {
            NotificationMessage message = childMessage(guardian.getStudentName());
            return new NotificationDraft(event.academyId(), guardian.getAccountId(), guardian.getName(), Role.PARENT,
                    NotificationType.RUN_STARTED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(),
                            "guardian:" + guardian.getAccountId() + ":" + guardian.getStudentId(), event.startedAt()),
                    guardian.getStudentId(), guardian.getStudentName(), null);
        }).toList();
    }

    /** 학생 — 계정이 연결된 학생만(로그인이 없는 학생은 알림을 받을 계정 자체가 없다). */
    private List<NotificationDraft> draftsForStudents(RunStartedEvent event, List<Long> studentIds) {
        List<Student> students = studentRepository
                .findAllByIdInAndAcademyIdAndAccountIdIsNotNull(studentIds, event.academyId());
        return students.stream().map(student -> {
            NotificationMessage message = childMessage(student.getName());
            return new NotificationDraft(event.academyId(), student.getAccountId(), student.getName(), Role.STUDENT,
                    NotificationType.RUN_STARTED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), "student:" + student.getAccountId(), event.startedAt()),
                    student.getId(), student.getName(), null);
        }).toList();
    }

    /** 학부모·학생 몫 문구 — 자녀 이름을 싣는다(Ruling 868). */
    private NotificationMessage childMessage(String studentName) {
        return runStartedChildComposer.compose(new RunStartedChildSubject(studentName));
    }
}
