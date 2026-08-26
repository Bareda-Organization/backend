package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.notification.entity.NotificationLog;
import src.backend.notification.event.NotificationAppended;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 알림을 아웃박스에 적재하는 <b>유일한 지점</b>(TECH_DECISIONS §7.2) — 상태 변경과 같은 트랜잭션에서
 * {@code push_state='pending'} 행을 남긴다.
 *
 * <p>{@code MANDATORY} 인 것이 이 클래스의 핵심이다. 자기 트랜잭션을 여는 순간 "상태 변경은
 * 롤백됐는데 알림만 남는" 창이 생기고, 그 알림은 <b>일어나지 않은 일</b>을 통지한다. 부를 자리가
 * 아니면 조용히 새 트랜잭션을 여는 대신 기동 실패에 준하는 예외로 알린다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutbox {

    private final NotificationLogRepository notificationLogRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 발송 대기 행을 적재하고 커밋 후 즉시 발송을 예약한다.
     *
     * <p>{@link NotificationAppended} 를 여기서 발행하는 이유는 적재한 쪽만 행 식별자를 알기
     * 때문이다 — 리스너가 {@code dedup_key} 로 다시 찾게 하면 같은 키의 옛 행을 집을 수 있다.
     *
     * @return 적재된 행의 식별자
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long append(NotificationDraft draft) {
        NotificationLog appended = notificationLogRepository.saveAndFlush(NotificationLog.forOutbox(
                draft.academyId(), draft.recipientAccountId(), draft.recipientName(), draft.recipientRole(),
                draft.type(), draft.title(), draft.body(), draft.dedupKey(), OffsetDateTime.now(clock)));
        eventPublisher.publishEvent(new NotificationAppended(appended.getId()));
        return appended.getId();
    }
}
