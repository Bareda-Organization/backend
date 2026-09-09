package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 근접 알림(NTF-04, API_SPEC §9.7 {@code arrive})을 옮기는 구독자 — {@link RunApproachingStopEvent}
 * 가 학생 1명당 1건이라 이 리스너도 1건마다 1번 실행되고, 그 호출마다 많아야 1행을 적재한다
 * ({@code RunAutoAlightedNotificationListener} 와 같은 형태).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@code RunRouteConfirmedNotificationListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class RunApproachingStopNotificationListener {

    /**
     * {@code dedup_key} 형태 — 대상 자리는 runId·stopId·studentId 뿐이다. <b>시각을 넣지 않는다</b> —
     * 최초 1회 발송의 실제 강제자는 {@code RunStopRepository#claimProximityNotice} 의 조건부
     * UPDATE(목표 15)이고, 이 키는 그 선점이 새더라도 같은 정차지·같은 학생에게 두 번 적재되는 것을
     * 막는 마지막 방어선이다. 시각을 넣으면 재판정 시각마다 값이 달라져 이 방어선이 통과만 하는
     * 장식이 된다(Phase 4 사고 형태 — 소비 시점 시계로 dedup_key 를 구성해 매번 새 값이 나온 것과
     * 같은 실수).
     */
    private static final String DEDUP_KEY_FORMAT = "approaching:%d:%d:%d";

    private final GuardianStudentRepository guardianStudentRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunApproachingStopEvent> runApproachingStopComposer;

    /**
     * 그 학생의 <b>첫 보호자</b> 1명에게만 적재한다({@code RunAutoAlightedNotificationListener} 와
     * 같은 규칙). 연결된 보호자가 없으면 아무 것도 하지 않는다.
     */
    @EventListener
    public void appendRunApproachingStop(RunApproachingStopEvent event) {
        List<GuardianAccountRecipient> guardians = guardianStudentRepository
                .findGuardianAccountsByAcademyId(event.academyId(), List.of(event.studentId()));
        if (guardians.isEmpty()) {
            return;
        }
        GuardianAccountRecipient firstGuardian = guardians.get(0);

        NotificationMessage message = runApproachingStopComposer.compose(event);
        notificationOutbox.append(new NotificationDraft(event.academyId(), firstGuardian.getAccountId(),
                firstGuardian.getName(), Role.PARENT, NotificationType.ARRIVE, message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.runId(), event.stopId(), event.studentId())));
    }
}
