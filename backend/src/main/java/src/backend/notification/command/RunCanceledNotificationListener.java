package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;
import src.backend.run.entity.Run;
import src.backend.run.event.RunCanceledEvent;
import src.backend.run.repository.RunRepository;

/**
 * 확정 회차 취소를 {@code run_canceled} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자는 기사·동승자, Ruling 894 ⑤).
 *
 * <p>{@link RunRouteConfirmedNotificationListener} 와 같은 평범한 {@code @EventListener} 다 — 취소 트랜잭션 안에서 적재하므로
 * 취소가 롤백되면 이 행도 사라진다. 배정이 없는 회차 · 계정이 연결되지 않은 매니저는 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class RunCanceledNotificationListener {

    /** {@code dedup_key} 형태 — 대상 자리는 매니저 id({@link RunRouteConfirmedNotificationListener} 와 같은 근거), 판정 시각은 취소 시각이다. */
    private static final String DEDUP_KEY_FORMAT = "run_canceled:%d:%d:%s";

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<RunCanceledEvent> runCanceledComposer;

    /** 그 회차에 배치된 기사·동승자 각각 앞으로 발송 대기 행을 적재한다. */
    @EventListener
    public void appendRunCanceled(RunCanceledEvent event) {
        List<AssignedManagerAccountView> assignees = assignmentRepository
                .findAssignedManagerAccounts(event.academyId(), event.runId());
        if (assignees.isEmpty()) {
            return;
        }

        NotificationMessage message = runCanceledComposer.compose(event);
        String busNo = runRepository.findByIdAndAcademyId(event.runId(), event.academyId())
                .map(Run::getBusId)
                .flatMap(busId -> busRepository.findByIdAndAcademyId(busId, event.academyId()))
                .map(Bus::getBusNo)
                .orElse(null);
        for (AssignedManagerAccountView assignee : assignees) {
            if (assignee.accountId() == null) {
                continue;
            }
            notificationOutbox.append(new NotificationDraft(event.academyId(), assignee.accountId(),
                    assignee.name(), roleOf(assignee.role()), NotificationType.RUN_CANCELED,
                    message.title(), message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.runId(), assignee.managerId(), event.canceledAt()),
                    null, null, busNo, event.runId()));
        }
    }

    /** 운행 배치 역할({@code manager.role})을 계정 역할({@code account.role})로 옮긴다 — 값 이름이 같아 직접 대응한다. */
    private static Role roleOf(ManagerRole managerRole) {
        return managerRole == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
    }
}
