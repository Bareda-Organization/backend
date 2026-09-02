package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.event.RiderNoShowEvent;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.event.RiderStatusRevertedEvent;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.student.repository.GuardianAccountView;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 승하차·미승차 알림(BRD-01·02·04, API_SPEC §4.6) 적재 — {@link IntentNotificationListener} 와 같은
 * 형태로 도메인 이벤트를 구독해 {@link NotificationOutbox} 에만 적는다. 승하차 처리 커맨드가 이
 * 클래스를 직접 부르지 않는다(§7 규칙 17).
 *
 * <p>멱등(목표 12)의 1차 방어선은 이 리스너가 아니라 <b>이벤트를 발행하는 커맨드 서비스</b>다 — 같은
 * {@code client_key} 재전송은 이력을 새로 쌓지 않는 시점에 이벤트도 다시 발행하지 않아, 이 리스너는
 * 애초에 두 번 불리지 않는다. {@code dedup_key} 는 {@link NotificationOutbox} 의 통상적인 2차 방어선일
 * 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class BoardingNotificationListener {

    private static final String STATUS_CHANGED_DEDUP_KEY_FORMAT = "boarding_status_changed:%d:%d:%s";
    private static final String STATUS_REVERTED_DEDUP_KEY_FORMAT = "boarding_status_reverted:%d:%d:%s";
    private static final String NO_SHOW_PARENT_DEDUP_KEY_FORMAT = "no_show_parent:%d:%d:%s";
    private static final String NO_SHOW_STAFF_DEDUP_KEY_FORMAT = "no_show_staff:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final NotificationOutbox notificationOutbox;
    private final NotificationComposer<RiderStatusChangedEvent> riderStatusChangedComposer;
    private final NotificationComposer<RiderStatusRevertedEvent> riderStatusRevertedComposer;
    private final NotificationComposer<RiderNoShowEvent> noShowParentComposer;
    private final NotificationComposer<RiderNoShowEvent> noShowStaffComposer;

    /** 승차·하차(BRD-01·02) — 학부모에게만 적재한다(§4.6 표, 관계자는 실시간 현황 갱신뿐이라 로그 대상이 아니다). */
    @EventListener
    public void appendRiderStatusChanged(RiderStatusChangedEvent event) {
        // 되돌리기 기원 이벤트는 여기서 처리하지 않는다 — status() 가 되돌아간 결과값이라 "무엇이
        // 취소됐는지"를 말해주지 못해, 그대로 적재하면 사실과 다른 승차·하차 문구가 나간다(목표 13·14,
        // Ruling 219). 정정 알림은 appendRiderStatusReverted 가 별도로 적재한다.
        if (event.reverted()) {
            return;
        }
        List<GuardianAccountView> guardians = guardianStudentRepository
                .findActiveGuardianAccountsByStudentId(event.studentId(), event.academyId());
        if (guardians.isEmpty()) {
            return;
        }
        NotificationMessage message = riderStatusChangedComposer.compose(event);
        NotificationType type = switch (event.status()) {
            case "boarded" -> NotificationType.BOARDING;
            case "alighted" -> NotificationType.ALIGHTING;
            default -> throw new IllegalStateException("알 수 없는 승하차 상태: " + event.status());
        };
        for (GuardianAccountView guardian : guardians) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), guardian.getAccountId(),
                    guardian.getName(), Role.PARENT, type, message.title(), message.body(),
                    STATUS_CHANGED_DEDUP_KEY_FORMAT.formatted(event.runRiderId(), guardian.getAccountId(),
                            event.changedAt())));
        }
    }

    /**
     * 승하차 되돌리기 정정(BRD-05, 목표 13·14, Ruling 219) — 이미 나간 승차·하차 알림은 고치지 않고
     * 이 정정 알림을 새로 적재한다. 취소된 상태가 승차·하차가 아니면(WAITING·NO_SHOW 로부터의
     * 되돌리기) 조용히 건너뛴다 — 목표 13·14 는 승차·하차 취소만 범위로 하고, 그 외 상태에 대한 정정
     * 알림 문구는 사양에 없다(판단 근거로 보고에 남긴다).
     */
    @EventListener
    public void appendRiderStatusReverted(RiderStatusRevertedEvent event) {
        if (!"boarded".equals(event.canceledStatus()) && !"alighted".equals(event.canceledStatus())) {
            return;
        }
        List<GuardianAccountView> guardians = guardianStudentRepository
                .findActiveGuardianAccountsByStudentId(event.studentId(), event.academyId());
        if (guardians.isEmpty()) {
            return;
        }
        NotificationMessage message = riderStatusRevertedComposer.compose(event);
        NotificationType type = "boarded".equals(event.canceledStatus())
                ? NotificationType.BOARDING_CANCELED
                : NotificationType.ALIGHTING_CANCELED;
        for (GuardianAccountView guardian : guardians) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), guardian.getAccountId(),
                    guardian.getName(), Role.PARENT, type, message.title(), message.body(),
                    STATUS_REVERTED_DEDUP_KEY_FORMAT.formatted(event.runRiderId(), guardian.getAccountId(),
                            event.revertedAt())));
        }
    }

    /** 미승차(BRD-04) — 학부모 "즉시 발송" + 관계자 "미승차 카운트 +1" 둘 다 적재한다(§4.6 표, 목표 7). */
    @EventListener
    public void appendRiderNoShow(RiderNoShowEvent event) {
        appendToGuardians(event);
        appendToStaff(event);
    }

    private void appendToGuardians(RiderNoShowEvent event) {
        List<GuardianAccountView> guardians = guardianStudentRepository
                .findActiveGuardianAccountsByStudentId(event.studentId(), event.academyId());
        if (guardians.isEmpty()) {
            return;
        }
        NotificationMessage message = noShowParentComposer.compose(event);
        for (GuardianAccountView guardian : guardians) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), guardian.getAccountId(),
                    guardian.getName(), Role.PARENT, NotificationType.NO_SHOW, message.title(), message.body(),
                    NO_SHOW_PARENT_DEDUP_KEY_FORMAT.formatted(event.runRiderId(), guardian.getAccountId(),
                            event.changedAt())));
        }
    }

    private void appendToStaff(RiderNoShowEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }
        NotificationMessage message = noShowStaffComposer.compose(event);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.NO_SHOW, message.title(), message.body(),
                    NO_SHOW_STAFF_DEDUP_KEY_FORMAT.formatted(event.runRiderId(), recipient.accountId(),
                            event.changedAt())));
        }
    }
}
