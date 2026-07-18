package src.backend.sos.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * SOS 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.sos.command.SosCommandService}를 호출하지 않는다.
 */
@Service
public class SosQueryService {

    private final SosEventRepository sosEventRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public SosQueryService(SosEventRepository sosEventRepository,
                           StudentRepository studentRepository,
                           StudentGuardianRepository studentGuardianRepository) {
        this.sosEventRepository = sosEventRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<SosEventResponse> getMyEvents(AuthUser studentUser) {
        Student student = studentRepository.findByUserId(studentUser.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        return toResponses(sosEventRepository.findByStudentIdOrderByOccurredAtDesc(student.getId()));
    }

    @Transactional(readOnly = true)
    public List<SosEventResponse> getChildrenEvents(AuthUser parent) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return toResponses(sosEventRepository.findByStudentIdInOrderByOccurredAtDesc(studentIds));
    }

    @Transactional(readOnly = true)
    public List<SosEventResponse> getTenantEvents(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return toResponses(sosEventRepository.findByTenantIdOrderByOccurredAtDesc(effectiveTenant));
    }

    private List<SosEventResponse> toResponses(List<SosEvent> events) {
        return events.stream().map(SosEventResponse::from).toList();
    }
}
