package src.backend.global.redis;

import java.util.function.Supplier;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 모든 Redis 명령을 {@code redis} 서킷({@link CircuitBreaker})으로 감싼 {@link StringRedisTemplate} — 응답 없는 Redis 앞에서 명령마다 시간 상한(500ms)을
 * 기다리는 요청 스레드가 쌓이지 않게 한다. 실패가 이어지면 서킷이 열려 Redis 를 부르지 않고 곧바로 {@link RedisCircuitOpenException} 으로 실패하고, 호출부는
 * 그 예외를 Redis 오류와 똑같이 받아 대체 경로(DB 조회 · 직접 전송 · 다시 계산 · 이 인스턴스 안 기억)로 내려간다. Redis 가 돌아오면 서킷이 스스로 닫혀 Redis
 * 경로로 돌아온다(Ruling 919).
 *
 * <p>템플릿 한 곳에서 감싸므로 Redis 를 쓰는 클래스마다 코드를 고치지 않고, 한 서킷을 모두가 나눠 쓴다 — Redis 는 하나의 의존 대상이라 한 사용처가 겪은 실패가
 * 다른 사용처의 대기도 줄인다. 모든 명령이 {@code execute} 두 갈래({@link RedisCallback} · {@link SessionCallback}) 중 하나로 들어오므로 그 둘만 감싼다.
 * 서킷이 세는 것은 {@code DataAccessException}(연결 실패 · 시간 상한 초과)뿐이라 값 직렬화 같은 호출자 쪽 오류는 서킷을 열지 않는다.
 */
public class GuardedStringRedisTemplate extends StringRedisTemplate {

    private final CircuitBreaker circuitBreaker;

    public GuardedStringRedisTemplate(RedisConnectionFactory connectionFactory, CircuitBreaker circuitBreaker) {
        super(connectionFactory);
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
        return guarded(() -> super.execute(action, exposeConnection, pipeline));
    }

    @Override
    public <T> T execute(SessionCallback<T> session) {
        return guarded(() -> super.execute(session));
    }

    private <T> T guarded(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw new RedisCircuitOpenException(e);
        }
    }
}
