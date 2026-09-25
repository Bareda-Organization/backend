package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.event.RunEndedEvent;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 회차 종료를 {@code run_ended} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자 관계자, USER_FLOWS UF-D-04 "관계자
 * 종료 통지", BR-110). 종료를 기록한 트랜잭션 안에서 적재한다({@link RunStartedNotificationListener} 와 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class RunEndedNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 역할 접두 + 관계자 계정(BR-007 과 같은 규칙). */
    private static final String DEDUP_KEY_FORMAT = "run_ended:%d:staff:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunEndedEvent> runEndedComposer;

    /** 그 학원 재직 관계자 전원에게 호차와 함께 적재한다. */
    @EventListener
    public void appendRunEnded(RunEndedEvent event) {
        NotificationMessage message = runEndedComposer.compose(event);
        String busNo = runRepository.findByIdAndAcademyId(event.runId(), event.academyId())
                .map(Run::getBusId)
                .flatMap(busId -> busRepository.findByIdAndAcademyId(busId, event.academyId()))
                .map(Bus::getBusNo)
                .orElse(null);
        for (AcademyStaffAccountView recipient : academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId())) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.RUN_ENDED, message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), recipient.accountId(), event.finishedAt()),
                    null, null, busNo));
        }
    }
}
