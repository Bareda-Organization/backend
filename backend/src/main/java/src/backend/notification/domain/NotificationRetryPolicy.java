package src.backend.notification.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import src.backend.notification.entity.NotificationLog;

/**
 * 발송 재시도를 언제 몇 번까지 하는지 정하는 <b>유일한 지점</b>(TECH_DECISIONS §7.2 "재시도 상한").
 *
 * <p>즉시 발송과 워커가 각자 상한을 들고 있으면 한쪽만 바뀌어, 상한을 넘긴 행이 워커에서는 포기되고
 * 즉시 경로에서는 계속 시도되는 상태가 된다.
 */
@Component
public class NotificationRetryPolicy {

    /**
     * 발송 시도 상한 — 넘기면 {@code push_state='failed'} 로 옮기고 레코드는 사후 추적용으로 남긴다.
     * 시드의 {@code failed} 행({@code notification_log} id=9)이 {@code push_attempts=3} 인 것과 같은 값이다.
     */
    public static final int MAX_ATTEMPTS = 3;

    /**
     * 첫 재시도까지의 최소 간격이자 지수 백오프의 밑값.
     *
     * <p>0 이 아니어야 하는 이유가 하나 더 있다 — 워커의 선점 조건이 "마지막 시도가 이 간격보다
     * 이전" 이라, 0 이면 방금 선점한 행을 <b>같은 틱의 다른 워커</b>가 그대로 다시 집는다.
     */
    public static final Duration MIN_RETRY_INTERVAL = Duration.ofMinutes(1);

    /** 이 시각보다 이전에 시도된 행만 다시 집을 수 있다 — 선점 조건이자 후보 조회 조건이다. */
    public OffsetDateTime attemptedBefore(OffsetDateTime now) {
        return now.minus(MIN_RETRY_INTERVAL);
    }

    /** 시도 횟수가 상한에 닿았는가 — 닿았으면 다음 상태는 {@code pending} 이 아니라 {@code failed} 다. */
    public boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }

    /**
     * 회차별 지수 백오프가 지났는가 — {@code MIN_RETRY_INTERVAL × 2^(시도횟수-1)} 이 기준이다.
     *
     * <p>아직 한 번도 시도하지 않은 행({@code last_attempt_at} 이 NULL)은 기다릴 것이 부재해 참이다.
     */
    public boolean retryDue(NotificationLog log, OffsetDateTime now) {
        if (log.getLastAttemptAt() == null) {
            return true;
        }
        Duration backoff = MIN_RETRY_INTERVAL.multipliedBy(1L << Math.max(0, log.getPushAttempts() - 1));
        return !now.isBefore(log.getLastAttemptAt().plus(backoff));
    }
}
