package src.backend.run.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;

/**
 * 운행 시작·도착 처리·변경 확인의 인가 — <b>역할이 틀린 것</b>과 <b>역할은 맞는데 이 회차에
 * 배치되지 않은 것</b>을 가른다(API_SPEC §4.4·§4.5·§4.11, RUN-02·04·05·06·07).
 *
 * <p>둘을 가르는 이유는 응답 코드가 다르기 때문이다 — 앞은 기능 자체를 못 쓰는 것이라 전용 코드
 * ({@code DRIVER_ONLY})로 안내하지만, 뒤는 배치 여부라는 정보를 응답에서 드러내면 안 되는 자원이라
 * 일반 {@code FORBIDDEN} 이다.
 *
 * <p>호출부가 학원으로 이미 좁힌 runId 를 넘긴다는 전제는 더 이상 성립하지 않는다 — <b>배치 확인이
 * 회차 조회보다 먼저 올 수 있다</b>(F3 Ruling 259(b), 예: {@code BoardingCommandService}·
 * {@code NoShowContactCommandService}). 그런데도 이 클래스가 직접 학원을 확인하지 않아도 안전한
 * 근거는 {@link AssignmentRepository}#{@code findByRunIdAndRole} 의 {@code @AcademyScopeExempt}
 * 가 아니라, <b>배치 생성 시점에 매니저와 회차의 학원이 이미 일치하도록 강제돼 있다는 것</b>이다
 * ({@code AssignmentCommandService#place} 가 매니저를 {@code requester.academyId()} 로 좁혀
 * 조회한다). 다른 학원의 매니저는 애초에 이 회차에 배치될 수 없으므로, runId 가 어느 학원 것이든
 * 이 클래스가 찾아낸 배치 행은 항상 그 매니저의 학원과 일치한다.
 */
@Component
@RequiredArgsConstructor
public class RunAssignmentAccess {

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    /**
     * 그 회차에 배치된 기사인지 — 아니면 역할에 따라 {@code DRIVER_ONLY} 또는 {@code FORBIDDEN}.
     *
     * @return 그 배치 행 — {@link #assertAssignedDriverOrEscort} 와 반환 형태를 맞춰 둔다(호출부가
     *         재조회 없이 이어 쓸 수 있게)
     */
    public Assignment assertAssignedDriver(AuthUser requester, Long runId) {
        if (requester.role() != Role.DRIVER) {
            throw new BusinessException(ErrorCode.DRIVER_ONLY);
        }
        return assertAssigned(requester, runId, ManagerRole.DRIVER);
    }

    /**
     * 그 회차에 배치된 기사 또는 동승자인지(§4.11 변경 확인 — 둘 다 응답할 수 있다).
     *
     * @return 그 배치 행 — 변경 확인 처리(§4.11)가 이 행에 그대로 {@code ack} 를 남긴다. 재조회로
     *         같은 조건(학원·회차·역할·담당자 일치)을 다시 캐지 않기 위해 여기서 확보한 행을 그대로 돌려준다
     */
    public Assignment assertAssignedDriverOrEscort(AuthUser requester, Long runId) {
        ManagerRole managerRole = switch (requester.role()) {
            case DRIVER -> ManagerRole.DRIVER;
            case ESCORT -> ManagerRole.ESCORT;
            default -> throw new BusinessException(ErrorCode.FORBIDDEN);
        };
        return assertAssigned(requester, runId, managerRole);
    }

    /**
     * 그 회차에 배치된 동승자인지(F3 S1, API_SPEC §4.9 지연 알림 — 기사 호출은 대상 밖). 역할이 아니면
     * {@link #assertAssignedDriver} 와 대칭으로 전용 코드({@code ESCORT_ONLY})를, 역할은 맞지만 이
     * 회차에 배치되지 않았으면 일반 {@code FORBIDDEN} 을 던진다 — 배치 여부를 응답에서 드러내지
     * 않는다는 이 클래스의 규칙(위 클래스 자바독)을 그대로 잇는다.
     *
     * @return 그 배치 행
     */
    public Assignment assertAssignedEscort(AuthUser requester, Long runId) {
        if (requester.role() != Role.ESCORT) {
            throw new BusinessException(ErrorCode.ESCORT_ONLY);
        }
        return assertAssigned(requester, runId, ManagerRole.ESCORT);
    }

    private Assignment assertAssigned(AuthUser requester, Long runId, ManagerRole managerRole) {
        Manager manager = managerRepository.findByAccountId(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        Assignment assignment = assignmentRepository.findByRunIdAndRole(runId, managerRole)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!assignment.getManagerId().equals(manager.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return assignment;
    }
}
