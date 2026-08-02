package src.backend.schedule.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.schedule.dto.LocationChangeRequestResponse;
import src.backend.schedule.entity.LocationChangeRequest;
import src.backend.schedule.repository.spec.LocationChangeRequestRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * 등하원 위치 변경 요청 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.schedule.command.LocationChangeCommandService}를 호출하지 않는다.
 */
@Service
public class LocationChangeQueryService {

    private final LocationChangeRequestRepository locationChangeRequestRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public LocationChangeQueryService(LocationChangeRequestRepository locationChangeRequestRepository,
                                      StudentGuardianRepository studentGuardianRepository) {
        this.locationChangeRequestRepository = locationChangeRequestRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<LocationChangeRequestResponse> getChildrenRequests(AuthUser parent) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return toResponses(locationChangeRequestRepository.findByStudentIdInOrderByCreatedAtDesc(studentIds));
    }

    @Transactional(readOnly = true)
    public List<LocationChangeRequestResponse> getTenantRequests(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return toResponses(locationChangeRequestRepository.findByTenantIdOrderByCreatedAtDesc(effectiveTenant));
    }

    private List<LocationChangeRequestResponse> toResponses(List<LocationChangeRequest> requests) {
        return requests.stream().map(LocationChangeRequestResponse::from).toList();
    }
}
