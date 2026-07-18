package src.backend.notification.query.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.query.spec.NotificationQueryService;
import src.backend.notification.repository.spec.NotificationLogRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

@Service
public class NotificationQueryServiceImpl implements NotificationQueryService {

    private final NotificationLogRepository notificationLogRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public NotificationQueryServiceImpl(NotificationLogRepository notificationLogRepository,
                                        StudentGuardianRepository studentGuardianRepository) {
        this.notificationLogRepository = notificationLogRepository;
        this.studentGuardianRepository = studentGuardianRepository;
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
