package src.backend.sos.service.impl;

import src.backend.sos.service.spec.SosService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.notification.NotificationThresholds;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.service.spec.NotificationService;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * {@link SosService} 기본 구현.
 * 발신·에스컬레이션 모두 {@link NotificationService}의 멱등 notify 를 그대로 재사용한다 —
 * "3분마다 재확인해도 같은 이벤트는 한 번만 에스컬레이션"이 dedupKey 로 자동 보장된다.
 */
@Service
public class SosServiceImpl implements SosService {

    private final SosEventRepository sosEventRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final NotificationService notificationService;

    public SosServiceImpl(SosEventRepository sosEventRepository,
                          StudentRepository studentRepository,
                          StudentGuardianRepository studentGuardianRepository,
                          NotificationService notificationService) {
        this.sosEventRepository = sosEventRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public SosEventResponse trigger(AuthUser studentUser, SosTriggerRequest req) {
        Student student = studentRepository.findByUserId(studentUser.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));

        SosEvent saved = sosEventRepository.save(SosEvent.builder()
                .tenantId(student.getTenant().getId())
                .studentId(student.getId())
                .lat(req.lat())
                .lng(req.lng())
                .occurredAt(LocalDateTime.now())
                .build());

        String dedupKey = NotificationService.dedupKey(
                NotificationType.SOS, student.getId(), saved.getOccurredAt().toLocalDate(), "trigger:" + saved.getId());
        notificationService.notify(NotificationType.SOS, saved.getTenantId(), student.getId(),
                dedupKey, student.getName() + " 학생이 긴급 SOS를 요청했습니다");

        return SosEventResponse.from(saved);
    }

    @Override
    @Transactional
    public SosEventResponse acknowledge(AuthUser admin, Long id) {
        SosEvent event = findEventForAdmin(admin, id);
        event.acknowledge(admin.userId());
        return SosEventResponse.from(event);
    }

    @Override
    @Transactional
    public SosEventResponse resolve(AuthUser admin, Long id) {
        SosEvent event = findEventForAdmin(admin, id);
        event.resolve(admin.userId());
        return SosEventResponse.from(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosEventResponse> getMyEvents(AuthUser studentUser) {
        Student student = studentRepository.findByUserId(studentUser.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        return toResponses(sosEventRepository.findByStudentIdOrderByOccurredAtDesc(student.getId()));
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public List<SosEventResponse> getTenantEvents(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return toResponses(sosEventRepository.findByTenantIdOrderByOccurredAtDesc(effectiveTenant));
    }

    @Override
    @Transactional
    public void escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now().minus(NotificationThresholds.SOS_ESCALATION);
        List<SosEvent> overdue = sosEventRepository.findByStatusAndOccurredAtBefore(SosStatus.OPEN, cutoff);
        for (SosEvent event : overdue) {
            LocalDate date = event.getOccurredAt().toLocalDate();
            String dedupKey = NotificationService.dedupKey(
                    NotificationType.SOS, event.getStudentId(), date, "escalate:" + event.getId());
            notificationService.notify(NotificationType.SOS, event.getTenantId(), event.getStudentId(),
                    dedupKey, "SOS(#" + event.getId() + ")가 3분간 미확인 상태입니다 — 플랫폼관리자 확인 필요");
        }
    }

    private SosEvent findEventForAdmin(AuthUser admin, Long id) {
        SosEvent event = sosEventRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SOS 기록을 찾을 수 없습니다"));
        if (!admin.isPlatformAdmin() && !admin.belongsToTenant(event.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return event;
    }

    private List<SosEventResponse> toResponses(List<SosEvent> events) {
        return events.stream().map(SosEventResponse::from).toList();
    }
}
