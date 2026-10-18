package src.backend.global.redis;

import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * {@code redis} 헬스 구성 요소 — Spring 의 기본 지표와 같이 Redis 에 {@code PING} 하지만 Redis 가 안 될 때 {@code DOWN} 이 아니라 {@code UNKNOWN} 을 낸다.
 * 최신 좌표 · 방송 · 미리보기 · 감사 묶기가 대체 경로로 서비스를 이어가므로(Ruling 919) Redis 가 서버를 헬스 DOWN(503)으로 만들면 컨테이너 헬스체크와 외부 감시 {@code /healthz} 가
 * 서비스 중인 서버를 빼고, 그 상태로 다시 시작한 백엔드는 건강해지지 못한다(Ruling 932). 전체 헬스는 {@code UNKNOWN} 을 {@code UP} 보다 낮게 보므로 DB 가 살아 있으면 {@code UP} 이다.
 * 구성 요소 자체는 {@code UP} 이 아니라 메인 관리자 시스템 상태 칸({@code SystemHealthReader})이 Redis 끊김을 계속 알리고, 경보는 {@code RedisFallbackActive} 다.
 *
 * <p>Boot 의 기본 지표는 같은 이름({@code redisHealthIndicator})의 빈이 있으면 물러난다.
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final DataRedisHealthIndicator ping;

    public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.ping = new DataRedisHealthIndicator(connectionFactory);
    }

    @Override
    public Health health(boolean includeDetails) {
        Health pinged = ping.health(includeDetails);
        return Status.UP.equals(pinged.getStatus()) ? pinged : Health.unknown().withDetails(pinged.getDetails()).build();
    }

    @Override
    public Health health() {
        return health(true);
    }
}
