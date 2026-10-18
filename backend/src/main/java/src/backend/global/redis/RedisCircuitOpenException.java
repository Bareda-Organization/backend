package src.backend.global.redis;

import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * {@code redis} 서킷이 열려 Redis 를 부르지 않고 거절했다는 신호 — Redis 연결 실패와 같은 종류의 예외라 호출부가 이미 가진 "Redis 가 안 되면 대체 경로" 분기
 * ({@code DataAccessException} · {@code RuntimeException} 을 잡는 곳)가 그대로 받는다.
 */
public class RedisCircuitOpenException extends RedisConnectionFailureException {

    public RedisCircuitOpenException(Throwable cause) {
        super("Redis 서킷이 열려 있어 Redis 를 부르지 않았다", cause);
    }
}
