package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 등원 최종 도착의 자동 하차를 {@code alighting} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자
 * 학부모, Phase 9 goal 9). {@link RunAutoAlightedEvent} 가 학생 1명당 1건이라 이 리스너도 1건마다
 * 1번 실행되고, 그 호출마다 <b>많아야 1행</b>을 적재한다 — "알림 행 수 = 자동 하차 인원 수"가
 * 이 1:1 대응에서 나온다.
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@link RunRouteConfirmedNotificationListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class RunAutoAlightedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 studentId 다(회차당 학생 1명에 이벤트도 1건이라 유일하다). */
    private static final String DEDUP_KEY_FORMAT = "alighting:%d:%d:%s";

    private final GuardianStudentRepository guardianStudentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunAutoAlightedEvent> runAutoAlightedComposer;

    /**
     * 그 학생의 <b>첫 보호자</b> 1명에게만 적재한다 — 보호자가 여럿이어도 자동 하차 알림은 대표
     * 1명으로 좁힌다({@link RunStartedNotificationListener#appendToGuardians} 와 같은 규칙).
     * 연결된 보호자가 없으면(드묾) 아무 것도 하지 않는다.
     */
    @EventListener
    public void appendRunAutoAlighted(RunAutoAlightedEvent event) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), List.of(event.studentId()));
        if (guardians.isEmpty()) {
            return;
        }
        GuardianAccountRecipient firstGuardian = guardians.get(0);

        NotificationMessage message = runAutoAlightedComposer.compose(event);
        notificationOutbox.append(new NotificationDraft(event.academyId(), firstGuardian.getAccountId(),
                firstGuardian.getName(), Role.PARENT, NotificationType.ALIGHTING, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.studentId(), event.alightedAt())));
    }
}
