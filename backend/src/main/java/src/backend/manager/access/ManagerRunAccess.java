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
     * 특정 회차 1건에 대한 접근을 판정한다(§4.2·§4.3) — 회차가 없거나, 학원이 다르거나, 학원은 맞지만
     * 이 매니저가 배치되지 않았거나, 셋 다 똑같이 {@code 403 FORBIDDEN} 이다(§1.11 "매니저 앱 회차
     * 자원의 403 FORBIDDEN(배치되지 않은 회차, §1.5)" 명시, Ruling 259(b)).
     *
     * <p>배치 조회({@code assignmentRepository.findByRunIdAndManagerId}) 를 회차 조회보다 <b>먼저</b>
     * 한다 — 이전에는 회차 조회를 앞세워 학원이 다르면 {@code 404 RUN_NOT_FOUND} 를 먼저 던졌는데,
     * §1.11 은 이 자원군 전체를 403 하나로 명시해 그 404 분기 자체가 스펙 위반이었다(review-f3-r4
     * #38~44). 이 순서로 바꿔도 정보가 새지 않는 것은 {@code manager} 가 이미
     * {@link #requireManager} 로 요청 학원에 묶여 있고, 배치는 생성 시점에 매니저·회차가 같은 학원일
     * 때만 만들어지기 때문이다({@code AssignmentCommandService#place} 참고) — 그래서 이 매니저에게
     * 배치가 없다는 사실 하나만으로 "회차가 없다" · "타 학원 회차다" · "이 학원 회차인데 배치가
     * 없다" 세 경우가 서로 구별되지 않고 전부 같은 403 으로 답해진다.
     *
     * <p>배치를 확인한 뒤의 {@code runRepository.findById} 는 그 회차를 못 찾을 상황을 상정하지
     * 않는다(배치가 가리키는 회차는 FK 로 보장된다) — 그런데도 {@code orElseThrow} 를 둔 것은
     * 방어적 안전망이지 정상 분기가 아니다.
     */
    public RunAssignment requireAssignedRun(AuthUser requester, Long runId) {
        Manager manager = requireManager(requester);
        Assignment assignment = assignmentRepository.findByRunIdAndManagerId(runId, manager.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        return new RunAssignment(run, assignment);
    }

    /** 판정을 통과한 회차와 그 배치 — {@code assignment.role} 이 응답의 {@code role_in_run} 이다. */
    public record RunAssignment(Run run, Assignment assignment) {
    }
}
