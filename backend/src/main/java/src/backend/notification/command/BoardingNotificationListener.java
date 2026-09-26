package src.backend.notification.command;

import java.util.List;
import java.util.Locale;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Role;
import src.backend.location.event.StopDepartedEvent;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.RiderStatusChangedSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountView;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승하차·미승차 학부모 알림(BRD-01·02·04, API_SPEC §4.6) 적재 — {@link IntentNotificationListener}
 * 와 같은 형태로 도메인 이벤트를 구독해 {@link NotificationOutbox} 에만 적는다. 승하차 처리 커맨드가
 * 이 클래스를 직접 부르지 않는다(§7 규칙 17).
 *
 * <p><b>발송 시점이 상태 변경 즉시가 아니라 그 승하차지를 출발할 때다(Ruling 308, IMPLEMENTATION_PLAN §8.23 T3)</b> —
 * {@link #appendStopDeparted} 가 {@link StopDepartedEvent} 를 구독해 그 시점의 확정 결과를 학생별로
 * 1건씩 적재한다. 출발 전 되돌리기(승차↔하차↔대기)를 몇 번 반복해도 알림은 나가지 않으므로, 되돌리기
 * 전용 정정 알림({@code Ruling 219})은 더 이상 필요 없어 폐기됐다 — 정정할 대상 자체가 없다(출발
 * 전 수정은 발송 전이다).
 *
 * <p>멱등(목표 12)의 1차 방어선은 이 리스너가 아니라 <b>{@code claimDeparture} 조건부 UPDATE</b>다 —
 * 같은 정차지가 여러 경로로 중복 선점을 시도해도 {@link StopDepartedEvent} 는 최초 1회만 발행된다
 * ({@link src.backend.location.proximity.StopDepartureService} 참고). {@code dedup_key} 는
 * {@link NotificationOutbox} 의 통상적인 2차 방어선일 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class BoardingNotificationListener {

    private static final String STOP_DEPARTED_DEDUP_KEY_FORMAT = "stop_departed:%d:%d:%s";
    private static final String NO_SHOW_STAFF_DEDUP_KEY_FORMAT = "no_show_staff:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final StudentRepository studentRepository;
    private final RunRiderRepository runRiderRepository;
    private final NotificationOutbox notificationOutbox;
    private final NotificationComposer<RiderStatusChangedSubject> riderStatusChangedComposer;
    private final NotificationComposer<NoShowParentSubject> noShowParentComposer;
    private final NotificationComposer<RiderMarkedNoShowEvent> noShowStaffComposer;

    /**
     * 그 승하차지를 출발할 때(Ruling 308) 확정 결과를 학생별로 1건씩 적재한다 — 승차·하차·미승차
     * 학부모 알림(BRD-01·02·04)을 여기서 함께 다룬다. {@code waiting} 인 채 출발한 학생은 발송
     * 대상이 아니다(목표 9) — {@link RunRiderRepository#findFinalizedByRunIdAndStopId} 가 이미 그
     * 상태를 걸러 조회한다.
     */
    @EventListener
    public void appendStopDeparted(StopDepartedEvent event) {
        List<RunRider> finalized = runRiderRepository.findFinalizedByRunIdAndStopId(event.runId(), event.stopId());
        for (RunRider rider : finalized) {
            List<GuardianAccountView> guardians = guardianStudentRepository
                    .findActiveGuardianAccountsByStudentId(rider.getStudentId(), event.academyId());
            if (guardians.isEmpty()) {
                continue;
            }
            NotificationMessage message = composeFor(rider.getStatus(), guardians.get(0).getStudentName());
            NotificationType type = typeOf(rider.getStatus());
            for (GuardianAccountView guardian : guardians) {
                notificationOutbox.append(new NotificationDraft(event.academyId(), guardian.getAccountId(),
                        guardian.getName(), Role.PARENT, type, message.title(), message.body(),
                        STOP_DEPARTED_DEDUP_KEY_FORMAT.formatted(rider.getId(), guardian.getAccountId(),
                                event.departedAt()),
                        rider.getStudentId(), guardian.getStudentName(), null));
            }
        }
    }

    /** {@link RiderStatusChangedComposer}·{@link NoShowParentComposer} 를 최종 상태에 맞게 재사용한다. */
    private NotificationMessage composeFor(RiderStatus status, String studentName) {
        if (status == RiderStatus.NO_SHOW) {
            return noShowParentComposer.compose(new NoShowParentSubject(studentName));
        }
        return riderStatusChangedComposer.compose(new RiderStatusChangedSubject(statusName(status), studentName));
    }

    private NotificationType typeOf(RiderStatus status) {
        return switch (status) {
            case BOARDED -> NotificationType.BOARDING;
            case ALIGHTED -> NotificationType.ALIGHTING;
            case NO_SHOW -> NotificationType.NO_SHOW;
            default -> throw new IllegalStateException(
                    "findFinalizedByRunIdAndStopId 가 걸러내지 않은 상태: " + status);
        };
    }

    private static String statusName(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 미승차(BRD-04) 관계자 갈래 — 학부모 갈래(BRD-04)는 위 {@link #appendStopDeparted} 로 옮겨 갔지만,
     * 관계자 갈래는 되돌리기로 뒤집히는 결과 통보가 아니라 <b>현황 신호</b>라 즉시 발송을 유지한다
     * (Ruling 311).
     */
    @EventListener
    public void appendRiderNoShow(RiderMarkedNoShowEvent event) {
        appendToStaff(event);
    }

    /** 관계자 수신자 전원이 같은 학생을 가리키므로 학생 이름은 대상자 순회 전에 한 번만 조회한다. */
    private void appendToStaff(RiderMarkedNoShowEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }
        NotificationMessage message = noShowStaffComposer.compose(event);
        String studentName = studentRepository.findById(event.studentId()).map(Student::getName).orElse(null);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.NO_SHOW, message.title(), message.body(),
                    NO_SHOW_STAFF_DEDUP_KEY_FORMAT.formatted(event.runRiderId(), recipient.accountId(),
                            event.changedAt()),
                    event.studentId(), studentName, null));
        }
    }
}
