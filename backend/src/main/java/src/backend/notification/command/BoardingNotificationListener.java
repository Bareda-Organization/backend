package src.backend.notification.command;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.event.RiderRecordedAfterDepartureEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.RiderStatusChangedSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.StopDepartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승하차·미승차 학부모 알림(BRD-01·02·04, API_SPEC §4.6) 적재 — {@link IntentNotificationListener}
 * 와 같은 형태로 도메인 이벤트를 구독해 {@link NotificationOutbox} 에만 적는다. 승하차 처리 커맨드가
 * 이 클래스를 직접 부르지 않는다(§7 규칙 17).
 *
 * <p><b>발송 시점이 상태 변경 즉시가 아니라 그 승하차지를 출발할 때다(Ruling 308)</b> —
 * {@link #appendStopDeparted} 가 {@link StopDepartedEvent} 를 구독해 그 시점의 확정 결과를 학생별로
 * 1건씩 적재한다. 출발 전 되돌리기(승차↔하차↔대기)를 몇 번 반복해도 알림은 나가지 않으므로, 되돌리기
 * 전용 정정 알림({@code Ruling 219})은 더 이상 필요 없어 폐기됐다 — 정정할 대상 자체가 없다(출발
 * 전 수정은 발송 전이다). 단 이미 출발 처리된 승하차지에 뒤늦게 기록된 승차·하차·미승차는 출발 때 묶음이 다시 오지 않으므로
 * {@link #appendRecordedAfterDeparture} 가 기록 즉시 보낸다(Ruling 878).
 *
 * <p>멱등의 1차 방어선은 이 리스너가 아니라 <b>{@code claimDeparture} 조건부 UPDATE</b>다 —
 * 같은 정차지가 여러 경로로 중복 선점을 시도해도 {@link StopDepartedEvent} 는 최초 1회만 발행된다
 * ({@link src.backend.run.command.StopDepartureService} 참고). {@code dedup_key} 는
 * {@link NotificationOutbox} 의 통상적인 2차 방어선일 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class BoardingNotificationListener {

    private static final String STOP_DEPARTED_DEDUP_KEY_FORMAT = "stop_departed:%d:%d:%s";
    private static final String RECORDED_AFTER_DEPARTURE_DEDUP_KEY_FORMAT = "recorded_after_departure:%d:%d:%s";
    private static final String NO_SHOW_STAFF_DEDUP_KEY_FORMAT = "no_show_staff:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final StudentRepository studentRepository;
    private final RunRiderRepository runRiderRepository;
    private final RunRepository runRepository;
    private final NotificationOutbox notificationOutbox;
    private final NotificationComposer<RiderStatusChangedSubject> riderStatusChangedComposer;
    private final NotificationComposer<NoShowParentSubject> noShowParentComposer;
    private final NotificationComposer<RiderMarkedNoShowEvent> noShowStaffComposer;

    /**
     * 그 승하차지를 출발할 때(Ruling 308) 확정 결과를 학생별로 1건씩 적재한다 — 승차·하차·미승차
     * 학부모 알림(BRD-01·02·04)을 여기서 함께 다룬다. {@code waiting} 인 채 출발한 학생은 발송
     * 대상이 아니다 — {@link RunRiderRepository#findFinalizedByRunIdAndStopId} 가 이미 그
     * 상태를 걸러 조회한다.
     *
     * <p>보호자 조회는 학생마다 따로 묻지 않고 <b>학생 id 전부를 한 번에</b> 묻는다(BR-134) —
     * {@link GuardianStudentRepository#findActiveGuardianAccountsByStudentIds} 결과를 학생별로
     * 묶어 두고 그 그룹으로 순회한다. 적재도 학생별로 하지 않고 정차지 전체의 초안을 모아 <b>한 번에</b> 한다
     * ({@link NotificationOutbox#appendAll}, BR-374 — 문장 수가 탑승자·보호자 수에 비례하지 않게).
     */
    @EventListener
    public void appendStopDeparted(StopDepartedEvent event) {
        List<RunRider> finalized = runRiderRepository.findFinalizedByRunIdAndStopId(event.runId(), event.stopId());
        if (finalized.isEmpty()) {
            return;
        }
        Direction direction = runRepository.findByIdAndAcademyId(event.runId(), event.academyId()).orElseThrow()
                .getDirection();
        List<Long> studentIds = finalized.stream().map(RunRider::getStudentId).distinct().toList();
        Map<Long, List<GuardianAccountRecipient>> guardiansByStudentId = guardianStudentRepository
                .findActiveGuardianAccountsByStudentIds(event.academyId(), studentIds).stream()
                .collect(Collectors.groupingBy(GuardianAccountRecipient::getStudentId));
        List<NotificationDraft> drafts = new ArrayList<>();
        for (RunRider rider : finalized) {
            drafts.addAll(parentDrafts(event.academyId(),
                    new ConfirmedResult(rider.getId(), rider.getStudentId(), rider.getStatus(), direction,
                            event.departedAt()),
                    guardiansByStudentId.getOrDefault(rider.getStudentId(), List.of()),
                    STOP_DEPARTED_DEDUP_KEY_FORMAT));
        }
        notificationOutbox.appendAll(drafts);
    }

    /**
     * 이미 출발 처리된 승하차지에서 뒤늦게 기록된 승차·하차·미승차(Ruling 878)는 출발 때 묶음 발송이 다시 오지 않으므로 기록 즉시 그
     * 학생의 학부모에게 보낸다 — 문구 · 수신자 · 알림 종류(설정 토글)는 출발 때 보내는 것과 같다. 중복은 발행 쪽이 막는다
     * ({@code run_stop} 행 잠금 아래에서 출발 여부를 읽고 발행하므로 출발 때 묶음과 같은 기록이 두 번 가지 않는다).
     */
    @EventListener
    public void appendRecordedAfterDeparture(RiderRecordedAfterDepartureEvent event) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findActiveGuardianAccountsByStudentIds(event.academyId(), List.of(event.studentId()));
        notificationOutbox.appendAll(parentDrafts(event.academyId(),
                new ConfirmedResult(event.runRiderId(), event.studentId(), event.status(), event.direction(),
                        event.changedAt()),
                guardians, RECORDED_AFTER_DEPARTURE_DEDUP_KEY_FORMAT));
    }

    /** 학부모 알림 1건의 재료인 탑승자 1명의 확정 결과 — {@code decidedAt} 은 중복 방지 키에 들어가는 확정(출발)·기록 시각이다. */
    private record ConfirmedResult(Long runRiderId, Long studentId, RiderStatus status, Direction direction,
            OffsetDateTime decidedAt) {
    }

    /** 학생 1명의 확정 결과를 보호자마다 1건씩 초안으로 만든다 — 보호자가 없으면 빈 목록이다. */
    private List<NotificationDraft> parentDrafts(Long academyId, ConfirmedResult result,
            List<GuardianAccountRecipient> guardians, String dedupKeyFormat) {
        if (guardians.isEmpty()) {
            return List.of();
        }
        NotificationMessage message = composeFor(result.status(), result.direction(),
                guardians.get(0).getStudentName());
        NotificationType type = typeOf(result.status());
        return guardians.stream()
                .map(guardian -> new NotificationDraft(academyId, guardian.getAccountId(), guardian.getName(),
                        Role.PARENT, type, message.title(), message.body(),
                        dedupKeyFormat.formatted(result.runRiderId(), guardian.getAccountId(), result.decidedAt()),
                        result.studentId(), guardian.getStudentName(), null))
                .toList();
    }

    /** {@link RiderStatusChangedComposer}·{@link NoShowParentComposer} 를 최종 상태에 맞게 재사용한다. */
    private NotificationMessage composeFor(RiderStatus status, Direction direction, String studentName) {
        if (status == RiderStatus.NO_SHOW) {
            return noShowParentComposer.compose(new NoShowParentSubject(studentName, direction));
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
