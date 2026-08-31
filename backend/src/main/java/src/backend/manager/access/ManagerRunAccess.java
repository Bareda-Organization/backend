package src.backend.manager.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 매니저 앱의 회차 접근 범위 판정 — "이 요청 주체는 어느 회차에 닿을 수 있는가" 를 답하는 <b>유일한
 * 지점</b>이다(API_SPEC §1.5 · §4.1~§4.3 · Phase 9 목표 16·17, {@code student.access
 * .GuardianChildAccess} 와 같은 근거로 신설).
 *
 * <p>범위를 좁히는 축이 <b>둘</b>이다. 요청 주체 → {@link Manager}(계정 경유, 삭제 여부 포함)와
 * {@link Manager} → 회차({@link Assignment} 경유). 앞을 건너뛰면 탈퇴 처리된 매니저의 옛 토큰이
 * 매니저 앱 데이터에 계속 닿고(목표 16, Ruling 148·192), 뒤를 건너뛰면 배치되지 않은 회차의 명단·
 * 경로가 다른 매니저에게 보인다(목표 17, Ruling 117·199).
 */
@Component
@RequiredArgsConstructor
public class ManagerRunAccess {

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    /**
     * 요청 주체의 매니저 레코드를 꺼낸다 — 연결이 없거나 삭제됐으면 {@code 403 FORBIDDEN}.
     *
     * <p>로그인 자체는 막지 않는다(§5.2·§6.7) — 계정 상태 게이트를 통과한 삭제 매니저 토큰이 여기
     * 이후의 매니저 앱 전용 자원에서만 막힌다(목표 16).
     */
    public Manager requireManager(AuthUser requester) {
        Manager manager = managerRepository.findByAccountId(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (manager.isDeleted()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return manager;
    }

    /**
     * 특정 회차 1건에 대한 접근을 판정한다(§4.2·§4.3) — 학원이 다르거나 회차가 없으면
     * {@code 404 RUN_NOT_FOUND}, 학원은 맞지만 이 매니저가 배치되지 않았으면 {@code 403 FORBIDDEN}
     * (§1.5, 목표 17)이다.
     *
     * <p>404 를 403 보다 먼저 판정한다 — 존재 자체를 학원 밖에는 드러내지 않고, 학원 안에서는 배치
     * 여부만 가른다({@code WAYPOINT_NOT_FOUND} 류와 같은 존재-비노출 원칙, ErrorCode 참고).
     */
    public RunAssignment requireAssignedRun(AuthUser requester, Long runId) {
        Manager manager = requireManager(requester);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Assignment assignment = assignmentRepository.findByRunIdAndManagerId(run.getId(), manager.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        return new RunAssignment(run, assignment);
    }

    /** 판정을 통과한 회차와 그 배치 — {@code assignment.role} 이 응답의 {@code role_in_run} 이다. */
    public record RunAssignment(Run run, Assignment assignment) {
    }
}
