package src.backend.attendance.query;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.dto.AttendanceExceptionResponse;
import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.global.common.ApprovalStatus;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 결석·휴원 신고 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.attendance.command.AttendanceCommandService}를 호출하지 않는다.
 */
@Service
public class AttendanceQueryService {

    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final StudentRepository studentRepository;

    public AttendanceQueryService(AttendanceExceptionRepository attendanceExceptionRepository,
                                  StudentGuardianRepository studentGuardianRepository,
                                  StudentRepository studentRepository) {
        this.attendanceExceptionRepository = attendanceExceptionRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.studentRepository = studentRepository;
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

    /**
     * 버스 배정 명단에서 해당 날짜 승인된 결석·휴원 학생을 제외한 "당일 실제 명단".
     * rideevent 명단·Phase 6 routing이 소비할 헬퍼 — 다른 모듈은 아직 이 메서드를 호출하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Student> getActiveRoster(Long busId, LocalDate date) {
        return studentRepository.findByAssignedBusId(busId).stream()
                .filter(student -> attendanceExceptionRepository
                        .findByStudentIdAndTargetDate(student.getId(), date).stream()
                        .noneMatch(exception -> exception.getStatus() == ApprovalStatus.APPROVED))
                .toList();
    }

    /**
     * 테넌트 전체 활성 로스터(결석 제외) — F4 자동배차가 버스 배정과 무관하게 "이 학원 전체 학생 중
     * 오늘 등하원해야 하는 학생"을 구할 때 쓴다. {@link #getActiveRoster}와 달리 배정된 버스 여부와
     * 무관하게 테넌트 전체를 본다(미배정 학생 포함).
     */
    @Transactional(readOnly = true)
    public List<Student> getActiveRosterForTenant(Long tenantId, LocalDate date) {
        return studentRepository.findByTenantId(tenantId).stream()
                .filter(student -> attendanceExceptionRepository
                        .findByStudentIdAndTargetDate(student.getId(), date).stream()
                        .noneMatch(exception -> exception.getStatus() == ApprovalStatus.APPROVED))
                .toList();
    }

    private List<AttendanceExceptionResponse> toResponses(List<AttendanceException> exceptions) {
        return exceptions.stream().map(AttendanceExceptionResponse::from).toList();
    }
}
