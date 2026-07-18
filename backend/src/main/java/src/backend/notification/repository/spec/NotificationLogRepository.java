package src.backend.notification.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.notification.entity.NotificationLog;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByDedupKey(String dedupKey);

    List<NotificationLog> findByStudentIdInOrderByCreatedAtDesc(List<Long> studentIds);

    List<NotificationLog> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
