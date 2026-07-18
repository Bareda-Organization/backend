package src.backend.notification.service.impl;

import src.backend.notification.service.spec.NotificationService;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.repository.spec.NotificationLogRepository;
import src.backend.notification.sender.NotificationSender;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * {@link NotificationService} 기본 구현.
 * 발송 파이프라인: dedupKey 존재 확인 → (있으면 무시) → 저장 → {@link NotificationSender} 호출.
 * exists 체크와 save 사이에 경쟁이 있을 수 있어(동시 틱 등), unique 제약 위반을 잡아 최종 방어한다.
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;
    private final StudentGuardianRepository studentGuardianRepository;

    public NotificationServiceImpl(NotificationLogRepository notificationLogRepository,
                                   NotificationSender notificationSender,
                                   StudentGuardianRepository studentGuardianRepository) {
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSender = notificationSender;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Override
    @Transactional
    public void notify(NotificationType type, Long tenantId, Long studentId, String dedupKey, String message) {
        if (notificationLogRepository.existsByDedupKey(dedupKey)) {
            return; // 이미 발송된 이벤트 — 조용히 무시(멱등)
        }
        NotificationLog saved;
        try {
            saved = notificationLogRepository.save(NotificationLog.builder()
                    .tenantId(tenantId).studentId(studentId).type(type)
                    .dedupKey(dedupKey).message(message).build());
        } catch (DataIntegrityViolationException e) {
            log.debug("[notify] 중복 발송 경쟁 감지, 무시: dedupKey={}", dedupKey);
            return; // 동시 요청이 먼저 저장 — unique 제약이 최종 방어
        }
        notificationSender.send(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getChildrenNotifications(AuthUser parent) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return notificationLogRepository.findByStudentIdInOrderByCreatedAtDesc(studentIds).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getTenantNotifications(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return notificationLogRepository.findByTenantIdOrderByCreatedAtDesc(effectiveTenant).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
