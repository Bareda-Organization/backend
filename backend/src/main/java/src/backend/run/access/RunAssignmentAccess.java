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
 * <p>호출부는 <b>학원으로 이미 좁힌 runId</b> 를 넘겨야 한다 — {@link AssignmentRepository}#
 * {@code findByRunIdAndRole} 이 {@code @AcademyScopeExempt} 인 전제가 그것이다. 이 클래스가 직접
 * 학원을 확인하지 않는 것은 실수가 아니라, 그 확인이 이미 회차 조회(404 RUN_NOT_FOUND) 쪽 책임이기
 * 때문이다.
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
