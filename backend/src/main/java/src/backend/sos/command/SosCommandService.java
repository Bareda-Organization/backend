package src.backend.sos.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.notification.NotificationThresholds;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;
import src.backend.sos.event.SosEscalatedEvent;
import src.backend.sos.event.SosTriggeredEvent;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * SOS 발신/확인/종료/에스컬레이션 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * 발신·에스컬레이션 모두 직접 알림 호출 대신 {@link SosTriggeredEvent}/{@link SosEscalatedEvent}를
 * 발행해(AFTER_COMMIT → Kafka) 알림 모듈과의 직접 결합을 없앤다.
 */
@Service
public class SosCommandService {

    private final SosEventRepository sosEventRepository;
    private final StudentRepository studentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SosCommandService(SosEventRepository sosEventRepository,
                             StudentRepository studentRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.sosEventRepository = sosEventRepository;
        this.studentRepository = studentRepository;
        this.eventPublisher = eventPublisher;
    }

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

        eventPublisher.publishEvent(SosTriggeredEvent.of(saved, student.getName()));
        return SosEventResponse.from(saved);
    }

    @Transactional
    public SosEventResponse acknowledge(AuthUser admin, Long id) {
        SosEvent event = findEventForAdmin(admin, id);
        event.acknowledge(admin.userId());
        return SosEventResponse.from(event);
    }

    @Transactional
    public SosEventResponse resolve(AuthUser admin, Long id) {
        SosEvent event = findEventForAdmin(admin, id);
        event.resolve(admin.userId());
        return SosEventResponse.from(event);
    }

    @Transactional
    public void escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now().minus(NotificationThresholds.SOS_ESCALATION);
        List<SosEvent> overdue = sosEventRepository.findByStatusAndOccurredAtBefore(SosStatus.OPEN, cutoff);
        for (SosEvent event : overdue) {
            eventPublisher.publishEvent(SosEscalatedEvent.of(event));
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
}
