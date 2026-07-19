package src.backend.attendance.command;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.dto.AttendanceExceptionResponse;
import src.backend.attendance.dto.CreateAttendanceExceptionRequest;
import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.event.AttendanceApprovedEvent;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * 결석·휴원 신고 생성/승인/반려 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * 승인 시 직접 알림 호출 대신 {@link AttendanceApprovedEvent}를 발행해(AFTER_COMMIT → Kafka)
 * 다른 모듈과의 직접 결합을 없앤다.
 */
@Service
public class AttendanceCommandService {

    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AttendanceCommandService(AttendanceExceptionRepository attendanceExceptionRepository,
                                    StudentGuardianRepository studentGuardianRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.attendanceExceptionRepository = attendanceExceptionRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AttendanceExceptionResponse create(AuthUser parent, CreateAttendanceExceptionRequest req) {
        Student student = requireGuardianOf(parent, req.studentId());

        AttendanceException saved = attendanceExceptionRepository.save(AttendanceException.builder()
                .tenantId(student.getTenant().getId())
                .studentId(student.getId())
                .type(req.type())
                .targetDate(req.targetDate())
                .reason(req.reason())
                .build());

        return AttendanceExceptionResponse.from(saved);
    }

    @Transactional
    public AttendanceExceptionResponse approve(AuthUser admin, Long id) {
        AttendanceException exception = findForAdmin(admin, id);
        exception.approve(admin.userId());
        eventPublisher.publishEvent(AttendanceApprovedEvent.of(exception));
        return AttendanceExceptionResponse.from(exception);
    }

    @Transactional
    public AttendanceExceptionResponse reject(AuthUser admin, Long id) {
        AttendanceException exception = findForAdmin(admin, id);
        exception.reject(admin.userId());
        return AttendanceExceptionResponse.from(exception);
    }

    private Student requireGuardianOf(AuthUser parent, Long studentId) {
        return studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .filter(s -> s.getId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "자녀가 아닙니다"));
    }

    private AttendanceException findForAdmin(AuthUser admin, Long id) {
        AttendanceException exception = attendanceExceptionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "신청을 찾을 수 없습니다"));
        if (!admin.isPlatformAdmin() && !admin.belongsToTenant(exception.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return exception;
    }
}
