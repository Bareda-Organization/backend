package src.backend.schedule.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.schedule.dto.ScheduleChangeRequestResponse;
import src.backend.schedule.entity.ScheduleChangeRequest;
import src.backend.schedule.repository.spec.ScheduleChangeRequestRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * 등하원 시간 변경 요청 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.schedule.command.ScheduleCommandService}를 호출하지 않는다.
 */
@Service
public class ScheduleQueryService {

    private final ScheduleChangeRequestRepository scheduleChangeRequestRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public ScheduleQueryService(ScheduleChangeRequestRepository scheduleChangeRequestRepository,
                                StudentGuardianRepository studentGuardianRepository) {
        this.scheduleChangeRequestRepository = scheduleChangeRequestRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<ScheduleChangeRequestResponse> getChildrenRequests(AuthUser parent) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return toResponses(scheduleChangeRequestRepository.findByStudentIdInOrderByRequestedDateDesc(studentIds));
    }

    @Transactional(readOnly = true)
    public List<ScheduleChangeRequestResponse> getTenantRequests(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return toResponses(scheduleChangeRequestRepository.findByTenantIdOrderByRequestedDateDesc(effectiveTenant));
    }

    private List<ScheduleChangeRequestResponse> toResponses(List<ScheduleChangeRequest> requests) {
        return requests.stream().map(ScheduleChangeRequestResponse::from).toList();
    }
}
