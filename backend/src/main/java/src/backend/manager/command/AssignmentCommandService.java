package src.backend.manager.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.websocket.AccountSessionsRevokeEvent;
import src.backend.manager.domain.AssignmentConflictDetector;
import src.backend.manager.dto.AssignedManagerResponse;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.dto.AssignmentRequest;
import src.backend.manager.dto.AssignmentWarning;
import src.backend.manager.dto.ManagerRunWindow;
import src.backend.manager.dto.RunAssignmentResponse;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 회차별 매니저 배치(MGR-05·06, API_SPEC §5.14).
 *
 * <p><b>경고 축과 차단 축을 섞지 않는다.</b> 근무 시간·중복 배치 충돌은 저장되고 경고로만 나가며
 * (Ruling 152), 회차당 기사 1명·동승자 1명은 {@code uk_assignment_run_role} 이 <b>차단</b>한다.
 *
 * <p>동승자 <b>자동</b> 배정은 여기 없다 — 소요 시간(노선 계산의 산출물)이 있어야 근무 시간 충돌을
 * 판정할 수 있다(Ruling 153).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AssignmentCommandService {

    private final RunRepository runRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final AssignmentConflictDetector conflictDetector;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 회차에 기사·동승자를 배치한다(§5.14) — 지정하지 않은 자리는 <b>그대로 둔다</b>.
     *
     * <p>충돌이 있어도 저장한다(Ruling 152). 응답의 {@code assignments[]} 는 이번 요청이 바꾼 것만이
     * 아니라 그 회차의 현재 배치 전부다 — 기사만 바꾼 요청이 동승자를 지운 것처럼 보이지 않게 한다.
     *
     * <p>임시 취소된 회차는 {@code 409 RUN_CANCELED} 다(BR-210, Ruling 376). 회차를 <b>공유 잠금</b>({@code FOR SHARE})으로
     * 다시 읽어 판정한다(BR-269) — 취소({@code RunCancellation})는 같은 행을 쓰기 잠금으로 잠그므로, 취소가 커밋되기 전에
     * 들어온 배치는 커밋을 기다린 뒤 취소된 상태를 보고 끝나고 배치가 먼저면 취소가 배치 커밋을 기다린다. 쓰기 잠금이 아니라
     * 공유 잠금인 것은 같은 자리를 동시에 채우는 두 요청이 서로를 막지 않아야 {@code 409 DUPLICATE_ASSIGNMENT} 로 갈리는
     * 계약({@code AssignmentConcurrencyTest})이 남기 때문이다.
     */
    public RunAssignmentResponse assign(AuthUser requester, Long runId, AssignmentRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기사·동승자 중 최소 하나를 지정해야 합니다");
        }
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        entityManager.refresh(run, LockModeType.PESSIMISTIC_READ);
        if (run.isCanceled()) {
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
        if (run.getStatus() == RunStatus.MOVING || run.getStatus() == RunStatus.FINISHED) {
            throw new BusinessException(ErrorCode.RUN_ALREADY_STARTED);
        }
        List<AssignmentWarning> warnings = AssignmentRoleUniqueGuard.enforce(() -> {
            List<AssignmentWarning> collected = new ArrayList<>();
            collected.addAll(place(requester, run, ManagerRole.DRIVER, request.driverManagerId()));
            collected.addAll(place(requester, run, ManagerRole.ESCORT, request.escortManagerId()));
            assignmentRepository.flush();
            return collected;
        });
        return new RunAssignmentResponse(run.getId(), assignmentsOf(requester, run), warnings);
    }

    /**
     * 자리 하나를 채운다 — 이미 채워져 있으면 <b>교체</b>다(§5.14).
     *
     * <p>충돌 판정을 저장 <b>전</b>에 한다. 저장 뒤에 하면 방금 넣은 배치가 "같은 시각의 다른 회차"
     * 후보에 섞여 자기 자신을 중복으로 세는 경우가 생긴다.
     *
     * <p>자리에 <b>새로 들어간</b> 매니저에게만 {@link AssignmentChangedEvent} 를 낸다({@code assignment_changed}
     * 알림, §9.7) — 같은 매니저를 다시 지정한 것은 배치가 바뀐 것이 아니다. 구독자는 커밋 후에 처리하므로
     * 자리 경합으로 롤백된 요청({@code 409 DUPLICATE_ASSIGNMENT})의 이벤트는 알림이 되지 않는다.
     *
     * @param managerId {@code null} 이면 이 자리를 건드리지 않는다는 뜻이라 경고도 나오지 않는다
     */
    private List<AssignmentWarning> place(AuthUser requester, Run run, ManagerRole role, Long managerId) {
        if (managerId == null) {
            return List.of();
        }
        Manager manager = findManagerForShare(requester, role, managerId);
        List<AssignmentWarning> warnings = detectConflicts(requester, run, manager, role);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<Assignment> current = assignmentRepository.findByRunIdAndRole(run.getId(), role);
        Long previousManagerId = current.map(Assignment::getManagerId).orElse(null); // 교체되면 사라질 값 — reassign 이 덮기 전에 읽는다
        boolean newlyPlaced = current.map(assignment -> !assignment.getManagerId().equals(manager.getId()))
                .orElse(true);
        writeOrReplace(requester, run, manager, role, current, now);
        if (newlyPlaced) {
            announcePlacement(run, manager, role, now, previousManagerId);
        }
        return warnings;
    }

    /** 같은 학원·역할의 삭제되지 않은 매니저를 공유 잠금으로 읽는다 — 없으면 {@code 404 MANAGER_NOT_FOUND}. */
    private Manager findManagerForShare(AuthUser requester, ManagerRole role, Long managerId) {
        return managerRepository
                .findForShareByIdAndAcademyIdAndRoleAndDeletedAtIsNull(managerId, requester.academyId(), role)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGER_NOT_FOUND));
    }

    /** 이 매니저의 전날~다음날 다른 회차 근무와 견주어 충돌 경고를 모은다 — 저장은 하지 않는다. */
    private List<AssignmentWarning> detectConflicts(AuthUser requester, Run run, Manager manager, ManagerRole role) {
        List<ManagerRunWindow> otherWindows = assignmentRepository.findManagerRunWindows(requester.academyId(),
                manager.getId(), run.getId(), run.getServiceDate().minusDays(1), run.getServiceDate().plusDays(1));
        return conflictDetector.detect(run, manager, role, otherWindows);
    }

    /** 현재 배치가 있으면 매니저를 교체하고, 없으면 새 배치를 저장한다. */
    private void writeOrReplace(AuthUser requester, Run run, Manager manager, ManagerRole role,
            Optional<Assignment> current, OffsetDateTime now) {
        current.ifPresentOrElse(
                assignment -> assignment.reassign(manager.getId(), now, requester.accountId()),
                () -> assignmentRepository.save(Assignment.uponAssignment(run.getAcademyId(), run.getId(), manager.getId(),
                        role, now, requester.accountId())));
    }

    /** 자리에 새로 들어간 매니저에게 배치 알림 이벤트를 내고, 빠진 매니저의 세션을 닫는다. */
    private void announcePlacement(Run run, Manager manager, ManagerRole role, OffsetDateTime now,
            Long previousManagerId) {
        eventPublisher.publishEvent(new AssignmentChangedEvent(run.getId(), run.getAcademyId(), manager.getId(),
                role, now));
        revokeReplacedSessions(previousManagerId);
    }

    /** 자리에서 빠진 매니저의 열린 세션을 커밋 직후 닫는다 — 그 회차 채널을 계속 받지 못하게. */
    private void revokeReplacedSessions(Long previousManagerId) {
        if (previousManagerId == null) {
            return;
        }
        managerRepository.findById(previousManagerId).map(Manager::getAccountId)
                .ifPresent(accountId -> eventPublisher.publishEvent(new AccountSessionsRevokeEvent(Set.of(accountId))));
    }

    /** 그 회차의 현재 배치 전부 — 매니저 이름을 함께 읽는다. */
    private List<AssignedManagerResponse> assignmentsOf(AuthUser requester, Run run) {
        return assignmentRepository.findAssignedManagers(requester.academyId(), List.of(run.getId())).stream()
                .map(AssignedManagerView::toResponse)
                .toList();
    }
}
