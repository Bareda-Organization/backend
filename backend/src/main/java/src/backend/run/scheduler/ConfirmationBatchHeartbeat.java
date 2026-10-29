package src.backend.run.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 확정 배치의 마지막 성공 시각 — 관리자 대시보드 {@code confirm_batch} 칸의 입력이다.
 *
 * <p>인스턴스 2대에서는 분산 락({@code run-confirmation})을 쥔 쪽만 틱을 돌므로, 인스턴스 안 시각만 보면 락을 못 쥔 인스턴스의 대시보드가 늘 "멈춤" 으로 나온다. 성공 시각을 Redis 키 하나(만료
 * {@value #TTL_HOURS}시간)에 남기고, 읽을 때는 이 인스턴스가 기억한 시각과 공유 시각 중 늦은 쪽을 쓴다. Redis 를 못 쓰면 인스턴스 안 시각만으로 판정한다 — 1대 운영은 그대로 동작하고, 2대에서
 * Redis 가 멈춘 동안 락을 못 쥔 쪽의 칸이 down 으로 나오는 것은 받아들인다(같은 순간 {@code api} 칸이 Redis 끊김으로 이미 down 이다).
 */
@Slf4j
@Component
public class ConfirmationBatchHeartbeat {

    static final String KEY = "schoolbus:run-confirmation:last-success";

    private static final int TTL_HOURS = 1;

    private static final Duration TTL = Duration.ofHours(TTL_HOURS);

    private final StringRedisTemplate redis;

    private final Clock clock;

    /** 기동 시점부터 잰다 — 한 번도 안 돈 인스턴스가 기동 직후부터 down 으로 나오지 않게 한다. */
    private volatile Instant localLastSuccess;

    public ConfirmationBatchHeartbeat(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        this.localLastSuccess = clock.instant();
    }

    /** 틱이 정상 반환한 지금을 기록한다 — 공유 저장소 쓰기가 실패해도 이 인스턴스 안 기록은 남는다. */
    public void markSuccess() {
        Instant now = clock.instant();
        localLastSuccess = now;
        try {
            redis.opsForValue().set(KEY, Long.toString(now.toEpochMilli()), TTL);
        } catch (RuntimeException e) {
            log.warn("확정 배치 마지막 성공 시각의 공유 기록 실패 — 이 인스턴스 안 기록만 남는다", e);
        }
    }

    /** 이 인스턴스가 아는 시각과 공유 시각 중 늦은 쪽 — 공유 읽기가 실패하면 이 인스턴스 안 시각이다. */
    public Instant lastSuccessAt() {
        Instant last = localLastSuccess;
        try {
            String shared = redis.opsForValue().get(KEY);
            if (shared != null) {
                Instant sharedAt = Instant.ofEpochMilli(Long.parseLong(shared));
                return sharedAt.isAfter(last) ? sharedAt : last;
            }
        } catch (RuntimeException e) {
            log.warn("확정 배치 마지막 성공 시각의 공유 읽기 실패 — 이 인스턴스 안 시각으로 판정한다", e);
        }
        return last;
    }
}
