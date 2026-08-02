package src.backend.schedule.command;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.schedule.dto.CreateScheduleChangeRequest;
import src.backend.schedule.dto.ScheduleChangeRequestResponse;
import src.backend.schedule.entity.ScheduleChangeRequest;
import src.backend.schedule.event.ScheduleResultEvent;
import src.backend.schedule.repository.spec.ScheduleChangeRequestRepository;
import src.backend.student.access.GuardianAccess;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 등하원 시간 변경 요청 생성/승인/반려 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * 승인·반려 모두 직접 알림 호출 대신 {@link ScheduleResultEvent}를 발행해(AFTER_COMMIT → Kafka)
 * 알림 모듈과의 직접 결합을 없앤다.
 */
@Service
public class ScheduleCommandService {

    private final ScheduleChangeRequestRepository scheduleChangeRequestRepository;
    private final GuardianAccess guardianAccess;
    private final StudentRepository studentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ScheduleCommandService(ScheduleChangeRequestRepository scheduleChangeRequestRepository,
                                  GuardianAccess guardianAccess,
                                  StudentRepository studentRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.scheduleChangeRequestRepository = scheduleChangeRequestRepository;
        this.guardianAccess = guardianAccess;
        this.studentRepository = studentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ScheduleChangeRequestResponse create(AuthUser parent, CreateScheduleChangeRequest req) {
        Student student = guardianAccess.requireGuardianOf(parent, req.studentId());

        ScheduleChangeRequest saved = scheduleChangeRequestRepository.save(ScheduleChangeRequest.builder()
                .tenantId(student.getTenant().getId())
                .studentId(student.getId())
                .requestedDate(req.requestedDate())
                .requestedTime(req.requestedTime())
                .reason(req.reason())
                .build());

        return ScheduleChangeRequestResponse.from(saved);
    }

    @Transactional
    public ScheduleChangeRequestResponse approve(AuthUser admin, Long id) {
        ScheduleChangeRequest request = findForAdmin(admin, id);
        request.approve(admin.userId());
        eventPublisher.publishEvent(ScheduleResultEvent.of(request, studentNameOf(request)));
        return ScheduleChangeRequestResponse.from(request);
    }

    @Transactional
    public ScheduleChangeRequestResponse reject(AuthUser admin, Long id) {
        ScheduleChangeRequest request = findForAdmin(admin, id);
        request.reject(admin.userId());
        eventPublisher.publishEvent(ScheduleResultEvent.of(request, studentNameOf(request)));
        return ScheduleChangeRequestResponse.from(request);
    }

    private ScheduleChangeRequest findForAdmin(AuthUser admin, Long id) {
        ScheduleChangeRequest request = scheduleChangeRequestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
        if (!admin.isPlatformAdmin() && !admin.belongsToTenant(request.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return request;
    }

    private String studentNameOf(ScheduleChangeRequest request) {
        return studentRepository.findById(request.getStudentId())
                .map(Student::getName)
                .orElse("알 수 없음");
    }
}
