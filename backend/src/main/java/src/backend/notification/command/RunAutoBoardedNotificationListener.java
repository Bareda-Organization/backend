package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RiderStatusChangedSubject;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.RunAutoBoardedEvent;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 하원 시작의 자동 승차를 {@code boarding} 알림으로 옮기는 구독자(NTF-01 · C-07, R51 H1) — 수신자는 그
 * 학생의 보호자 전원이고, 문구는 동승자가 승차 처리했을 때({@link BoardingNotificationListener})와 같다.
 * 알림 설정의 등하원(boarding) 토글은 발송 단계가 이미 {@code NotificationType.BOARDING} 으로 판정한다.
 *
 * <p>{@code @EventListener} 인 이유는 {@link RunAutoAlightedNotificationListener} 와 같다 — 운행 시작과 같은
 * 트랜잭션에서 적재한다.
 */
@Component
@RequiredArgsConstructor
public class RunAutoBoardedNotificationListener {

    /** {@code dedup_key} 형태 — 회차당 학생 1명에 이벤트가 1건이라 회차·보호자 계정·학생이면 유일하다. */
    private static final String DEDUP_KEY_FORMAT = "auto_boarded:%d:guardian:%d:%d";

    private final GuardianStudentRepository guardianStudentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RiderStatusChangedSubject> riderStatusChangedComposer;

    /** 자동 승차된 학생 전원의 보호자를 한 번에 조회해 초안을 모아 한 번에 적재한다. 보호자가 없는 학생은 건너뛴다. */
    @EventListener
    public void appendRunAutoBoarded(RunAutoBoardedEvent event) {
        List<NotificationDraft> drafts = guardianStudentRepository
                .findActiveGuardianAccountsByStudentIds(event.academyId(), event.studentIds()).stream()
                .map(guardian -> draftFor(event, guardian))
                .toList();
        notificationOutbox.appendAll(drafts);
    }

    private NotificationDraft draftFor(RunAutoBoardedEvent event, GuardianAccountRecipient guardian) {
        NotificationMessage message = riderStatusChangedComposer
                .compose(new RiderStatusChangedSubject("boarded", guardian.getStudentName()));
        return new NotificationDraft(event.academyId(), guardian.getAccountId(), guardian.getName(), Role.PARENT,
                NotificationType.BOARDING, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), guardian.getAccountId(), guardian.getStudentId()),
                guardian.getStudentId(), guardian.getStudentName(), null);
    }
}
