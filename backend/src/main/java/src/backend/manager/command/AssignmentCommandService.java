package src.backend.manager.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.domain.AssignmentConflictDetector;
import src.backend.manager.dto.AssignedManagerResponse;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.dto.AssignmentRequest;
import src.backend.manager.dto.AssignmentWarning;
import src.backend.manager.dto.RunAssignmentResponse;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 회차별 매니저 배치(MGR-05·06, API_SPEC §5.14).
 *
 * <p><b>경고 축과 차단 축을 섞지 않는다.</b> 근무 시간·중복 배치 충돌은 저장되고 경고로만 나가며
 * (Ruling 152), 회차당 기사 1명·동승자 1명은 {@code uk_assignment_run_role} 이 <b>차단</b>한다.
 *
 * <p>동승자 <b>자동</b> 배정은 여기 없다 — 소요 시간(노선 계산의 산출물)이 있어야 근무 시간 충돌을
 * 판정할 수 있어 Phase 6 소유다(Ruling 153).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AssignmentCommandService {

    /**
     * 회차당 역할 하나를 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code assignment} 에 CHECK 와 FK 가 더 있기 때문이다 — 제약을 가리지
     * 않고 통째로 409 로 옮기면 존재하지 않는 회차·매니저를 가리킨 FK 위반까지 "이미 배치된
     * 역할입니다" 로 답한다.
     */
    private static final String ASSIGNMENT_ROLE_UNIQUE_CONSTRAINT = "uk_assignment_run_role";

    private final RunRepository runRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final AssignmentConflictDetector conflictDetector;

    private final Clock clock;

    /**
     * 회차에 기사·동승자를 배치한다(§5.14) — 지정하지 않은 자리는 <b>그대로 둔다</b>.
     *
     * <p>충돌이 있어도 저장한다(Ruling 152). 응답의 {@code assignments[]} 는 이번 요청이 바꾼 것만이
     * 아니라 그 회차의 현재 배치 전부다 — 기사만 바꾼 요청이 동승자를 지운 것처럼 보이지 않게 한다.
     */
    public RunAssignmentResponse assign(AuthUser requester, Long runId, AssignmentRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기사·동승자 중 최소 하나를 지정해야 합니다");
        }
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<AssignmentWarning> warnings = enforcingUniqueRole(() -> {
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
     * @param managerId {@code null} 이면 이 자리를 건드리지 않는다는 뜻이라 경고도 나오지 않는다
     */
    private List<AssignmentWarning> place(AuthUser requester, Run run, ManagerRole role, Long managerId) {
        if (managerId == null) {
            return List.of();
        }
        Manager manager = managerRepository
                .findByIdAndAcademyIdAndRoleAndDeletedAtIsNull(managerId, requester.academyId(), role)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGER_NOT_FOUND));
        List<AssignmentWarning> warnings = conflictDetector.detect(run, manager, role);
        OffsetDateTime now = OffsetDateTime.now(clock);
        assignmentRepository.findByRunIdAndRole(run.getId(), role)
                .ifPresentOrElse(
                        assignment -> assignment.reassign(manager.getId(), now, requester.accountId()),
                        () -> assignmentRepository.save(Assignment.uponAssignment(run.getId(), manager.getId(),
                                role, now, requester.accountId())));
        return warnings;
    }

    /**
     * 자리 중복을 {@code 409 DUPLICATE_ASSIGNMENT} 로 옮기며 배치 작업을 실행한다.
     *
     * <p>순차 요청은 {@code findByRunIdAndRole} 이 교체로 처리하므로 이 제약이 발동하는 것은
     * <b>경합뿐</b>이다 — 동시 2요청이 같은 자리를 채우려 하면 둘 다 "빈 자리" 를 읽고 각자 INSERT 를
     * 보낸다. 그 거부를 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와 "누가 먼저 채웠다" 가
     * 구별되지 않는다({@code BusCommandService} 가 같은 자리를 이미 푼다).
     *
     * <p><b>저장 호출까지 이 안에 넣어야 한다.</b> 신규 배치는 {@code IDENTITY} 키를 받으려고
     * {@code save()} 시점에 INSERT 가 이미 나가므로, {@code flush()} 만 감싸면 제약 위반이 이
     * {@code try} 밖에서 터진다 — 실제로 그 형태로 {@code DataIntegrityViolationException} 이 그대로
     * 새는 것을 동시성 테스트가 잡았다. 교체 경로는 변경 감지라 {@code flush()} 가 있어야 나가므로
     * <b>두 경로가 다르고</b>, 둘 다 덮으려면 저장과 flush 를 함께 감싸는 형태뿐이다.
     *
     * <p>{@link jakarta.persistence.EntityManager} 가 아니라 <b>저장소의</b> flush 를 부르는 것도
     * 같은 이유로 중요하다 — 예외 번역은 {@code @Repository} 빈을 거칠 때만 붙어,
     * {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를 그대로 지나친다.
     */
    private <T> T enforcingUniqueRole(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException cve
                    && ASSIGNMENT_ROLE_UNIQUE_CONSTRAINT.equals(cve.getConstraintName())) {
                throw new BusinessException(ErrorCode.DUPLICATE_ASSIGNMENT);
            }
            throw e;
        }
    }

    /** 그 회차의 현재 배치 전부 — 매니저 이름을 함께 읽는다. */
    private List<AssignedManagerResponse> assignmentsOf(AuthUser requester, Run run) {
        return assignmentRepository.findAssignedManagers(requester.academyId(), List.of(run.getId())).stream()
                .map(AssignedManagerView::toResponse)
                .toList();
    }
}
