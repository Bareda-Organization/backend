package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.notification.domain.NotificationRetryPolicy;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 적재된 아웃박스 행 하나를 실제로 발송하고 결과를 행에 옮긴다 — 즉시 발송(커밋 직후)과 워커 재시도가
 * <b>같은 절차</b>를 쓴다(TECH_DECISIONS §7.2).
 *
 * <p>두 경로가 같은 코드를 쓰는 것이 중요하다. 나누면 상한·사유 기록·상태 전이가 두 벌이 되고, 한쪽만
 * 고쳤을 때 <b>양쪽 테스트가 계속 통과한다</b>.
 *
 * <p>이 클래스에 트랜잭션 애너테이션이 부재한 것은 의도다 — 발송은 트랜잭션 밖이어야 하고
 * (§7.5), 상태 전이만 저장소 메서드가 각자 {@code REQUIRES_NEW} 로 연다.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationLogRepository notificationLogRepository;

    private final NotificationRetryPolicy retryPolicy;

    private final PushSender pushSender;

    private final Clock clock;

    /** 발송 실패 사유를 {@code fail_reason}(varchar 200) 에 담을 때의 상한. */
    private static final int FAIL_REASON_MAX_LENGTH = 200;

    /**
     * 한 건을 발송한다. 발송 실패는 예외로 새어 나가지 않고 행에 기록된다 — 즉시 발송 경로가
     * {@code AFTER_COMMIT} 리스너라, 예외를 던지면 <b>이미 커밋된</b> 상태 변경의 응답이 뒤집힌다.
     */
    public void dispatch(Long notificationId) {
        NotificationLog target = notificationLogRepository.findById(notificationId).orElse(null);
        if (target == null) {
            return;
        }
        send(target);
    }

    /** 발송하고 결과를 옮긴다 — 성공은 {@code sent}, 실패는 사유를 남긴 채 상한에 따라 갈린다. */
    private void send(NotificationLog target) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            pushSender.send(PushMessage.from(target));
            notificationLogRepository.markSent(target.getId(), PushState.PENDING, PushState.SENT, now);
        } catch (RuntimeException e) {
            PushState nextState = retryPolicy.exhausted(target.getPushAttempts())
                    ? PushState.FAILED
                    : PushState.PENDING;
            notificationLogRepository.markAttemptFailed(target.getId(), PushState.PENDING, nextState,
                    failReason(e));
        }
    }

    /** 사유는 컬럼 길이를 넘지 않게 자른다 — 자르지 않으면 실패 기록 자체가 제약 위반으로 또 실패한다. */
    private String failReason(RuntimeException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        return reason.length() <= FAIL_REASON_MAX_LENGTH
                ? reason
                : reason.substring(0, FAIL_REASON_MAX_LENGTH);
    }
}
