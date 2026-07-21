package src.backend.notification.command.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import src.backend.notification.domain.NotificationLog;
import src.backend.notification.domain.NotificationType;
import src.backend.notification.infrastructure.spec.NotificationSender;
import src.backend.notification.repository.spec.NotificationLogRepository;

/**
 * 알림 발송 dedupKey 멱등 처리를 우선 검증한다(G5 2차) — 이미 발송된 이벤트는 조용히 무시,
 * exists 체크 통과 후 save 경쟁이 발생해도 unique 제약 위반을 최종 방어로 잡아 무시한다.
 */
class NotificationCommandServiceImplTest {

    private final NotificationLogRepository notificationLogRepository = mock(NotificationLogRepository.class);
    private final NotificationSender senderA = mock(NotificationSender.class);
    private final NotificationSender senderB = mock(NotificationSender.class);

    private final NotificationCommandServiceImpl service = new NotificationCommandServiceImpl(
            notificationLogRepository, List.of(senderA, senderB));

    @Test
    void notify_newDedupKey_savesAndSendsToAllSenders() {
        given(notificationLogRepository.existsByDedupKey("key1")).willReturn(false);
        NotificationLog saved = NotificationLog.builder()
                .tenantId(1L).studentId(10L).type(NotificationType.BOARD_DONE)
                .dedupKey("key1").message("승차 완료").build();
        given(notificationLogRepository.save(any(NotificationLog.class))).willReturn(saved);

        service.notify(NotificationType.BOARD_DONE, 1L, 10L, "key1", "승차 완료");

        verify(notificationLogRepository).save(any(NotificationLog.class));
        verify(senderA).send(saved);
        verify(senderB).send(saved);
    }

    @Test
    void notify_alreadyExistingDedupKey_skipsSilentlyWithoutSending() {
        given(notificationLogRepository.existsByDedupKey("key1")).willReturn(true); // 이미 발송됨

        service.notify(NotificationType.BOARD_DONE, 1L, 10L, "key1", "승차 완료");

        verify(notificationLogRepository, never()).save(any(NotificationLog.class));
        verifyNoInteractions(senderA, senderB);
    }

    @Test
    void notify_saveRaceCondition_catchesDataIntegrityViolationSilently() {
        given(notificationLogRepository.existsByDedupKey("key1")).willReturn(false); // 체크 시점엔 없었음
        given(notificationLogRepository.save(any(NotificationLog.class)))
                .willThrow(new DataIntegrityViolationException("unique constraint violation")); // 동시 요청이 먼저 저장

        service.notify(NotificationType.BOARD_DONE, 1L, 10L, "key1", "승차 완료");

        verifyNoInteractions(senderA, senderB);
    }
}
