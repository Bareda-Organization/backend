package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.WorkHours;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.assign.spec.AttendantAssignInput;
import src.backend.routing.assign.spec.AttendantAssigner;
import src.backend.routing.assign.spec.AttendantAssignment;
import src.backend.routing.assign.spec.AttendantCandidate;
import src.backend.routing.assign.spec.BusyWindow;
import src.backend.run.entity.Run;

/**
 * 확정 배치의 동승자 자동 배정(Ruling 330, ARCHITECTURE §8.2 ⑤) — 동승자 자리가 빈 회차에만 {@link AttendantAssigner}
 * 를 불러 배정하고 {@link AssignmentChangedEvent} 를 낸다. 수동 배치(§5.14)가 있으면 건드리지 않고, 후보가 없거나
 * 전원 충돌이면 빈 채로 둔다 — 확정을 실패시키지 않는다. 확정 저장 트랜잭션({@link RunConfirmationPersistence#persist})
 * 안에서만 부른다 — 확정이 롤백되면 배정도 되돌아간다.
 */
@Component
@RequiredArgsConstructor
class AttendantAutoAssignment {

    private final AssignmentRepository assignmentRepository;

    private final ManagerRepository managerRepository;

    private final AttendantAssigner attendantAssigner;

    private final ApplicationEventPublisher eventPublisher;

    /** @param estDurationMin ④ ETA 산출의 총 소요 — 근무 시간·중복 배치 판정의 회차 시간대 끝을 정한다 */
    void assignIfVacant(Run run, int estDurationMin, OffsetDateTime now) {
        if (assignmentRepository.findByRunIdAndRole(run.getId(), ManagerRole.ESCORT).isPresent()) {
            return;
        }
        List<AttendantCandidate> candidates = managerRepository
                .findAllByAcademyIdAndRoleAndDeletedAtIsNullOrderByIdAsc(run.getAcademyId(), ManagerRole.ESCORT)
                .stream()
                .map(manager -> new AttendantCandidate(manager.getId(),
                        WorkHours.fromColumnValue(manager.getWorkHours()), busyWindowsOf(run, manager.getId())))
                .toList();
        AttendantAssignment result = attendantAssigner.assign(new AttendantAssignInput(run.getId(),
                run.getAcademyId(), run.getDepartTime(), estDurationMin, candidates));
        if (result.managerId() == null) {
            return;
        }
        assignmentRepository.save(Assignment.uponAssignment(run.getId(), result.managerId(), ManagerRole.ESCORT, now,
                null));
        eventPublisher.publishEvent(new AssignmentChangedEvent(run.getId(), run.getAcademyId(), result.managerId(),
                ManagerRole.ESCORT, now));
    }

    /**
     * 그 매니저가 이미 배치된 다른 회차의 시간대. 소요가 비었으면 출발 시각부터 1분으로 본다 — {@link BusyWindow} 는
     * 빈 구간을 받지 않고, 수동 배치 판정({@code AssignmentConflictDetector#windowEnd})도 소요가 없으면 출발 시각
     * 한 점으로 접는다.
     */
    private List<BusyWindow> busyWindowsOf(Run run, Long managerId) {
        return assignmentRepository.findManagerRunWindows(run.getAcademyId(), managerId, run.getId()).stream()
                .map(window -> new BusyWindow(window.departTime(), window.departTime().plusMinutes(
                        Math.max(1, window.estDurationMin() == null ? 0 : window.estDurationMin()))))
                .toList();
    }
}
