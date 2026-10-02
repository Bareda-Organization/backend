package src.backend.notification.command;

import java.util.ArrayList;
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
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 비상 신고 접수·취소를 {@code emergency}·{@code emergency_canceled} 알림으로 옮기는 구독자
 * (EXC-04, Phase 11 T2 목표 6·9 — 수신자는 학원 관계자 전원 + 메인관리자 전원, 알림 설정과 무관하게
 * 항상 발송).
 *
 * <p>다른 리스너들({@link RunStartedNotificationListener} 등)과 같은 위치({@code
 * notification.command})에 둔다 — 발행측 모듈(exception)이 구독측(notification) 패키지를 직접
 * 참조하면 모듈 경계가 역방향으로 뚫린다({@code NotificationModuleIsolationTest}).
 *
 * <p>{@code @EventListener} 인 이유는 {@link RunStartedNotificationListener} 와 같다 — 이 이벤트는
 * 신고 접수·취소 커맨드 서비스가 이미 연 트랜잭션 안에서 발행되고, 그 트랜잭션이 롤백되면 이
 * 리스너가 적재한 행도 함께 롤백된다.
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

    /** 신고 접수(목표 6·9) — 학원 관계자 전원 + 메인관리자 전원에게 {@code emergency} 알림을 적재한다. */
    @EventListener
    public void appendRaised(EmergencyRaisedEvent event) {
        NotificationMessage message = emergencyRaisedComposer.compose(event);
        appendToStaffAndAdmins(event.academyId(), event.emergencyId(), RAISED_DEDUP_KEY_FORMAT,
                NotificationType.EMERGENCY, message, event.busNo());
    }

    /** 신고 취소(목표 6·9) — 같은 대상에게 {@code emergency_canceled} 알림을 적재한다. */
    @EventListener
    public void appendCanceled(EmergencyCanceledEvent event) {
        NotificationMessage message = emergencyCanceledComposer.compose(event);
        appendToStaffAndAdmins(event.academyId(), event.emergencyId(), CANCELED_DEDUP_KEY_FORMAT,
                NotificationType.EMERGENCY_CANCELED, message, event.busNo());
    }

    /**
     * 관계자·메인관리자 두 집합의 초안을 모아 한 번에 적재한다({@link NotificationOutbox#appendAll}, BR-354) — 비상 접수는
     * {@code client_key} 자문 잠금을 쥔 채 이 적재를 하므로 문장 수가 수신자 수에 비례하면 가장 빨라야 할 쓰기 경로가 늘어진다.
     */
    private void appendToStaffAndAdmins(Long academyId, Long emergencyId, String dedupKeyFormat, NotificationType type,
            NotificationMessage message, String busNo) {
        List<NotificationDraft> drafts = new ArrayList<>(
                draftsForStaff(academyId, emergencyId, dedupKeyFormat, type, message, busNo));
        drafts.addAll(draftsForMainAdmins(academyId, emergencyId, dedupKeyFormat, type, message, busNo));
        notificationOutbox.appendAll(drafts);
    }

    /** 학원 관계자 — 그 학원 재직 전원(회차의 특정 배치와 무관하다, {@code RunStartedNotificationListener} 와 같은 대상 규칙). */
    private List<NotificationDraft> draftsForStaff(Long academyId, Long emergencyId, String dedupKeyFormat,
            NotificationType type, NotificationMessage message, String busNo) {
        List<AcademyStaffAccountView> staff = academyStaffRepository.findActiveAccountsByAcademyId(academyId);
        return staff.stream()
                .map(recipient -> new NotificationDraft(academyId, recipient.accountId(), recipient.name(),
                        Role.STAFF, type, message.title(), message.body(),
                        dedupKeyFormat.formatted(emergencyId, recipient.accountId()), null, null, busNo))
                .toList();
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
    private List<NotificationDraft> draftsForMainAdmins(Long academyId, Long emergencyId, String dedupKeyFormat,
            NotificationType type, NotificationMessage message, String busNo) {
        List<Account> admins = accountRepository.findAllByRoleAndStatus(Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
        return admins.stream()
                .map(admin -> new NotificationDraft(academyId, admin.getId(), admin.getName(),
                        Role.SYSTEM_ADMIN, type, message.title(), message.body(),
                        dedupKeyFormat.formatted(emergencyId, admin.getId()), null, null, busNo))
                .toList();
    }
}
