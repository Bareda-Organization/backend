package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.RunAutoAlightedSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 등원 최종 도착의 자동 하차를 {@code alighting} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자
 * 학부모, Phase 9 goal 9). {@link RunAutoAlightedEvent} 가 학생 1명당 1건이라 이 리스너도 1건마다
 * 1번 실행되고, 그 호출마다 그 학생의 <b>보호자 수만큼</b> 적재한다(BR-073).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@link RunRouteConfirmedNotificationListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class RunAutoAlightedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 보호자 계정·studentId 다(회차당 학생 1명에 이벤트도 1건). */
    private static final String DEDUP_KEY_FORMAT = "alighting:%d:guardian:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunAutoAlightedSubject> runAutoAlightedComposer;

    /**
     * 그 학생의 보호자 전원에게 적재한다(BR-073 — 동승자 처리 하차와 같은 수신 규칙, §9.7 {@code alighting}). 초안을 모아
     * 한 번에 적재해({@link NotificationOutbox#appendAll}, BR-374) 문장 수가 보호자 수에 비례하지 않는다.
     * 연결된 보호자가 없으면(드묾) 아무 것도 하지 않는다.
     */
    @EventListener
    public void appendRunAutoAlighted(RunAutoAlightedEvent event) {
        List<NotificationDraft> drafts = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), List.of(event.studentId())).stream()
                .map(guardian -> draftFor(event, guardian))
                .toList();
        notificationOutbox.appendAll(drafts);
    }

    private NotificationDraft draftFor(RunAutoAlightedEvent event, GuardianAccountRecipient guardian) {
        NotificationMessage message = runAutoAlightedComposer
                .compose(new RunAutoAlightedSubject(guardian.getStudentName()));
        return new NotificationDraft(event.academyId(), guardian.getAccountId(),
                guardian.getName(), Role.PARENT, NotificationType.ALIGHTING, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), guardian.getAccountId(), event.studentId(),
                        event.alightedAt()),
                event.studentId(), guardian.getStudentName(), null);
    }
}
