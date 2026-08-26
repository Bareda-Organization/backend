package src.backend.manager.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

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
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;

/** 매니저 등록·수정·삭제(MGR-02·03·04, API_SPEC §5.13). */
@Service
@RequiredArgsConstructor
@Transactional
public class ManagerCommandService {

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final Clock clock;

    /** 매니저를 등록한다(§5.13) — 소속 학원은 토큰에서만 온다(§1.5). */
    public ManagerResponse register(AuthUser requester, ManagerRegisterRequest request) {
        Manager manager = Manager.register(requester.academyId(), new ManagerProfile(request.name(),
                request.phone(), toRole(request.role()), WorkHours.of(request.workHours())));
        return ManagerResponse.from(managerRepository.save(manager));
    }

    /** 매니저 정보를 고친다(§5.13) — 대상이 다른 학원이거나 이미 삭제됐으면 {@code 404 MANAGER_NOT_FOUND} 다. */
    public ManagerResponse update(AuthUser requester, Long managerId, ManagerUpdateRequest request) {
        Manager manager = findManageable(requester, managerId);
        manager.update(new ManagerProfile(request.name(), request.phone(), toRole(request.role()),
                WorkHours.of(request.workHours())));
        return ManagerResponse.from(manager);
    }

    /**
     * 매니저를 삭제한다(MGR-04, §5.13) — 회차에 배치돼 있으면 {@code 409 MANAGER_ASSIGNED} 이고
     * {@code deleted_at} 은 <b>NULL 로 남는다</b>.
     *
     * <p>배치 확인이 삭제보다 <b>먼저</b> 와야 한다 — soft delete 는 UPDATE 라 FK RESTRICT 가
     * 발동하지 않아, 순서를 뒤집으면 DB 가 되돌려 주지 않는다.
     */
    public void delete(AuthUser requester, Long managerId) {
        Manager manager = findManageable(requester, managerId);
        if (assignmentRepository.existsByManagerId(manager.getId())) {
            throw new BusinessException(ErrorCode.MANAGER_ASSIGNED);
        }
        manager.delete(OffsetDateTime.now(clock));
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
