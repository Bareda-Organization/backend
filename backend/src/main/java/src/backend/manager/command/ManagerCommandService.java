package src.backend.manager.command;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.ManagerRegisterRequest;
import src.backend.manager.dto.ManagerResponse;
import src.backend.manager.dto.ManagerUpdateRequest;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.entity.WorkHours;
import src.backend.manager.event.ManagerRoleChangedEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.RunStatus;

/** 매니저 등록·수정·삭제(MGR-02·03·04, API_SPEC §5.13). */
@Service
@RequiredArgsConstructor
@Transactional
public class ManagerCommandService {

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 매니저를 등록한다(§5.13) — 소속 학원은 토큰에서만 온다(§1.5). */
    public ManagerResponse register(AuthUser requester, ManagerRegisterRequest request) {
        Manager manager = Manager.register(requester.academyId(), new ManagerProfile(request.name(),
                request.phone(), toRole(request.role()), WorkHours.of(request.workHours())));
        return ManagerResponse.from(managerRepository.save(manager));
    }

    /**
     * 매니저 정보를 고친다(§5.13) — 대상이 다른 학원이거나 이미 삭제됐으면 {@code 404 MANAGER_NOT_FOUND} 다.
     *
     * <p>역할이 바뀌면 끝나지 않은 배치가 있을 때 {@code 409 MANAGER_ASSIGNED} 이고(배치 자리가 곧 역할이라
     * 통과시키면 그 자리에 권한 없는 사람이 남는다), 연결된 계정의 역할도 함께 바뀐다(BR-022).
     */
    public ManagerResponse update(AuthUser requester, Long managerId, ManagerUpdateRequest request) {
        Manager manager = findManageable(requester, managerId);
        ManagerRole role = toRole(request.role());
        boolean roleChanged = role != null && role != manager.getRole();
        if (roleChanged) {
            assertNoUnfinishedAssignment(manager);
        }
        manager.update(new ManagerProfile(request.name(), request.phone(), role, WorkHours.of(request.workHours())));
        if (roleChanged && manager.getAccountId() != null) {
            eventPublisher.publishEvent(new ManagerRoleChangedEvent(manager.getAccountId(), role));
        }
        return ManagerResponse.from(manager);
    }

    /**
     * 매니저를 삭제한다(MGR-04, §5.13) — 끝나지 않은 회차에 배치돼 있으면 {@code 409 MANAGER_ASSIGNED} 이고
     * {@code deleted_at} 은 <b>NULL 로 남는다</b>.
     *
     * <p>배치 확인이 삭제보다 <b>먼저</b> 와야 한다 — soft delete 는 UPDATE 라 FK RESTRICT 가
     * 발동하지 않아, 순서를 뒤집으면 DB 가 되돌려 주지 않는다.
     */
    public void delete(AuthUser requester, Long managerId) {
        Manager manager = findManageable(requester, managerId);
        assertNoUnfinishedAssignment(manager);
        manager.delete(OffsetDateTime.now(clock));
    }

    /** 삭제·역할 변경이 같은 기준("배치 해제 후", MGR-04)을 보도록 판정을 한 곳에 둔다. */
    private void assertNoUnfinishedAssignment(Manager manager) {
        if (assignmentRepository.existsUnfinishedByManagerId(manager.getId(), LocalDate.now(clock),
                RunStatus.FINISHED, RunStatus.MOVING)) {
            throw new BusinessException(ErrorCode.MANAGER_ASSIGNED);
        }
    }

    private Manager findManageable(AuthUser requester, Long managerId) {
        return managerRepository.findByIdAndAcademyIdAndDeletedAtIsNull(managerId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGER_NOT_FOUND));
    }

    /**
     * {@code driver}·{@code escort} 두 값만 받는다(§5.13 · §9.1) — 그 외는
     * {@code 422 VALIDATION_FAILED}.
     *
     * <p>이 값이 앱 권한을 결정하므로(C-06) 알 수 없는 문자열을 조용히 기본값으로 삼지 않는다 —
     * 그러면 오탈자 하나가 기사에게 승하차 기록 권한을 주거나 뺏는다.
     */
    private ManagerRole toRole(String role) {
        if (role == null) {
            return null;
        }
        try {
            return ManagerRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "매니저 역할이 아닙니다: " + role);
        }
    }
}
