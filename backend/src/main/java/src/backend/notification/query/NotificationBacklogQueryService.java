package src.backend.notification.query;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.health.NotificationBacklog;
import src.backend.notification.repository.NotificationLogRepository;

/** 발송 대기 적체 수(API_SPEC §6.18 {@code health[]} {@code notification}) — {@code ix_notification_log_pending}(대기 행만 담는 부분 인덱스)가 받친다. */
@Service
@RequiredArgsConstructor
class NotificationBacklogQueryService implements NotificationBacklog {

    private final NotificationLogRepository notificationLogRepository;

    @Override
    @Transactional(readOnly = true)
    public long countPendingCreatedBefore(OffsetDateTime threshold) {
        return notificationLogRepository.countPendingCreatedBefore(threshold);
    }
}
