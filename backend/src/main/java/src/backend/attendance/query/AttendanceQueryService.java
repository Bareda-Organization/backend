package src.backend.attendance.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.dto.AttendanceExceptionResponse;
import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * 결석·휴원 신고 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.attendance.command.AttendanceCommandService}를 호출하지 않는다.
 *
 * <p>"당일 실제 명단"(결석 승인분 제외) 조회는 여기 없다 —
 * {@link src.backend.attendance.roster.ActiveRosterReader} 로 옮겼다. 호출자에 Command 서비스가
 * 섞여 있어 이 클래스에 두면 §7("Command 가 Query 를 호출")을 어기기 때문이다.
 */
@Service
public class AttendanceQueryService {

    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public AttendanceQueryService(AttendanceExceptionRepository attendanceExceptionRepository,
                                  StudentGuardianRepository studentGuardianRepository) {
        this.attendanceExceptionRepository = attendanceExceptionRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<AttendanceExceptionResponse> getChildrenExceptions(AuthUser parent) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return toResponses(attendanceExceptionRepository.findByStudentIdInOrderByTargetDateDesc(studentIds));
    }

    @Transactional(readOnly = true)
    public List<AttendanceExceptionResponse> getTenantExceptions(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return toResponses(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(effectiveTenant));
    }

    private List<AttendanceExceptionResponse> toResponses(List<AttendanceException> exceptions) {
        return exceptions.stream().map(AttendanceExceptionResponse::from).toList();
    }
}
