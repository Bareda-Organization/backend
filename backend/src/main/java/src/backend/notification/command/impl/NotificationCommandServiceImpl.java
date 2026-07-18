package src.backend.notification.command.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.notification.command.spec.NotificationCommandService;
import src.backend.notification.domain.NotificationLog;
import src.backend.notification.domain.NotificationType;
import src.backend.notification.infrastructure.spec.NotificationSender;
import src.backend.notification.repository.spec.NotificationLogRepository;

/**
 * {@link NotificationCommandService} 기본 구현.
 * 발송 파이프라인: dedupKey 존재 확인 → (있으면 무시) → 저장 → 등록된 {@link NotificationSender}
 * 전부에게 fan-out(로그 기록 + WebSocket push 병행, Phase 3e).
 * exists 체크와 save 사이에 경쟁이 있을 수 있어(동시 틱 등), unique 제약 위반을 잡아 최종 방어한다.
 */
@Service
public class NotificationCommandServiceImpl implements NotificationCommandService {

    private static final Logger log = LoggerFactory.getLogger(NotificationCommandServiceImpl.class);

    private final NotificationLogRepository notificationLogRepository;
    private final List<NotificationSender> notificationSenders;

    public NotificationCommandServiceImpl(NotificationLogRepository notificationLogRepository,
                                          List<NotificationSender> notificationSenders) {
        this.notificationLogRepository = notificationLogRepository;
        this.notificationSenders = notificationSenders;
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
        notificationSenders.forEach(sender -> sender.send(saved));
    }
}
