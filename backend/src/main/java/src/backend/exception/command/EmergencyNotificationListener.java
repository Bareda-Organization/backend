package src.backend.exception.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 비상 신고 접수·취소를 {@code emergency}·{@code emergency_canceled} 알림으로 옮기는 구독자
 * (EXC-04, Phase 11 T2 목표 6·9 — 수신자는 학원 관계자 전원 + 메인관리자 전원, 알림 설정과 무관하게
 * 항상 발송).
 *
 * <p>이 클래스는 관례상 {@code notification.command} 패키지에 두는 다른 리스너들
 * ({@link src.backend.notification.command.RunStartedNotificationListener} 등)과 다르게
 * {@code exception.command} 에 둔다 — 이 태스크의 소유 경로가 {@code notification/domain/impl}
 * (컴포저)까지이고 {@code notification/command} 는 포함하지 않아, 다른 좌석이 동시에 건드릴 수
 * 있는 공용 패키지에 새 파일을 얹지 않기 위한 판단이다. {@link NotificationOutbox}·
 * {@link NotificationDraft}·{@link NotificationComposer} 는 전부 공개 API 라 이 패키지에서도
 * 그대로 재사용할 수 있다.
 *
 * <p>{@code @EventListener} 인 이유는 {@link src.backend.notification.command.RunStartedNotificationListener}
 * 와 같다 — 이 이벤트는 신고 접수·취소 커맨드 서비스가 이미 연 트랜잭션 안에서 발행되고, 그
 * 트랜잭션이 롤백되면 이 리스너가 적재한 행도 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class EmergencyNotificationListener {

    private static final String RAISED_DEDUP_KEY_FORMAT = "emergency:%d:%d";

    private static final String CANCELED_DEDUP_KEY_FORMAT = "emergency_canceled:%d:%d";

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<EmergencyRaisedEvent> emergencyRaisedComposer;

    private final NotificationComposer<EmergencyCanceledEvent> emergencyCanceledComposer;

    @EventListener
    public void appendRaised(EmergencyRaisedEvent event) {
        NotificationMessage message = emergencyRaisedComposer.compose(event);
        appendToStaff(event.academyId(), event.emergencyId(), RAISED_DEDUP_KEY_FORMAT, NotificationType.EMERGENCY,
                message);
        appendToMainAdmins(event.academyId(), event.emergencyId(), RAISED_DEDUP_KEY_FORMAT,
                NotificationType.EMERGENCY, message);
    }

    @EventListener
    public void appendCanceled(EmergencyCanceledEvent event) {
        NotificationMessage message = emergencyCanceledComposer.compose(event);
        appendToStaff(event.academyId(), event.emergencyId(), CANCELED_DEDUP_KEY_FORMAT,
                NotificationType.EMERGENCY_CANCELED, message);
        appendToMainAdmins(event.academyId(), event.emergencyId(), CANCELED_DEDUP_KEY_FORMAT,
                NotificationType.EMERGENCY_CANCELED, message);
    }

    /** 학원 관계자 — 그 학원 재직 전원(회차의 특정 배치와 무관하다, {@code RunStartedNotificationListener} 와 같은 대상 규칙). */
    private void appendToStaff(Long academyId, Long emergencyId, String dedupKeyFormat, NotificationType type,
            NotificationMessage message) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(academyId);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(academyId, recipient.accountId(), recipient.name(),
                    Role.STAFF, type, message.title(), message.body(),
                    dedupKeyFormat.formatted(emergencyId, recipient.accountId())));
        }
    }

    /**
     * 메인관리자 전원(목표 6) — {@code academy_id} 가 없는 역할이라(§1.5) 신고가 일어난 학원 조건으로
     * 좁히지 않고 {@link AccountRepository#findAllByRoleAndStatus} 로 전 학원 범위 대상을 그대로
     * 부른다.
     *
     * <p>{@link NotificationDraft} 의 {@code academyId} 자리에는 <b>수신자의</b> 학원(=null)이 아니라
     * <b>신고가 발생한</b> 학원을 싣는다 — {@code notification_log.academy_id} 가 NOT NULL 제약이라
     * null 을 넣을 수 없고, 이 알림이 "어느 학원 일" 인지를 로그가 여전히 답할 수 있어야 한다(메인관리자
     * 화면은 여러 학원의 알림을 한 목록에서 academy_id 로 구분해 보여준다).
     */
    private void appendToMainAdmins(Long academyId, Long emergencyId, String dedupKeyFormat, NotificationType type,
            NotificationMessage message) {
        List<Account> admins = accountRepository.findAllByRoleAndStatus(Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
        for (Account admin : admins) {
            notificationOutbox.append(new NotificationDraft(academyId, admin.getId(), admin.getName(),
                    Role.SYSTEM_ADMIN, type, message.title(), message.body(),
                    dedupKeyFormat.formatted(emergencyId, admin.getId())));
        }
    }
}
