package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 미승차 에스컬레이션 알림(목표 1, API_SPEC §4.8) 적재 — {@link BoardingNotificationListener} 와
 * 같은 형태로 도메인 이벤트를 구독해 {@link NotificationOutbox} 에만 적는다. 이벤트 발행처
 * ({@code NoShowEscalationPersistence})가 이 클래스를 직접 부르지 않는다(§7 규칙 17).
 *
 * <p>{@code BoardingNotificationListener} 에 함께 묶지 않고 별도 클래스로 둔 이유는 이 저장소의
 * 기존 관례 — {@code IntentNotificationListener} · {@code RunStartedNotificationListener} 처럼
 * 도메인 이벤트 발생원마다 리스너 클래스를 나눈다({@code BoardingNotificationListener} 자바독
 * 참고). 이 이벤트의 발생원은 승하차 커맨드가 아니라 에스컬레이션 스케줄러라 별도 클래스가
 * 맞다는 판단 — 확신 없는 지점으로 보고에 남긴다({@code notification/command/} 패키지가 이
 * 좌석의 소유 경로 표에 명시돼 있지 않으나, 다른 좌석도 이 경로를 점유하지 않는다).
 *
 * <p>멱등의 1차 방어선은 이 리스너가 아니라 {@code NoShowCaseRepository#escalateIfDue} 의
 * 조건부 UPDATE 다 — 영향받은 행이 0이면 발행처가 이벤트를 아예 발행하지 않아 이 리스너는
 * 애초에 두 번 불리지 않는다. {@code dedup_key} 는 {@link NotificationOutbox} 의 통상적인 2차
 * 방어선일 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class NoShowEscalationNotificationListener {

    private static final String DEDUP_KEY_FORMAT = "no_show_escalated:%d:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;
    private final NotificationOutbox notificationOutbox;
    private final NotificationComposer<NoShowEscalatedEvent> noShowEscalatedStaffComposer;

    /** 관계자에게만 적재한다(§4.8, 학부모 앞 알림은 미승차 발생 시점에 이미 나갔다). */
    @EventListener
    public void appendNoShowEscalated(NoShowEscalatedEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }
        NotificationMessage message = noShowEscalatedStaffComposer.compose(event);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.NO_SHOW_ESCALATED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.caseId(), recipient.accountId(), event.escalatedAt())));
        }
    }
}
