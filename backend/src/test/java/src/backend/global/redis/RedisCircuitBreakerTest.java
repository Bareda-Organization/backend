package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;

import src.backend.location.infrastructure.RedisClientConfig;
import testsupport.redis.OutageRedis;

/**
 * 응답이 없거나 멈춘 Redis 앞에서 요청 스레드가 묶이지 않는다 — 명령 시간 상한이 한 번의 호출을 끊고, 실패가 이어지면 차단 장치({@code redis} 서킷)가
 * 열려 Redis 를 부르지 않고 곧바로 실패해(= 호출부의 대체 경로로 내려가) 다시 켜지면 자동으로 닫힌다.
 *
 * <p>설정은 {@code application.yml} 을 그대로 읽은 실제 빈 조합이다({@code ApplicationContextRunner}). Redis 는 이 클래스 전용 컨테이너라
 * 멈추고 얼려도 다른 시험 클래스가 영향을 받지 않는다.
 */
class RedisCircuitBreakerTest {

    /** 서킷이 열리기까지 실패해야 하는 호출 수(yml 의 최소 표본). */
    private static final int CALLS_TO_OPEN = 5;

    /** Redis 를 부르지 않았다고 볼 상한 — 명령 시간 상한(500ms)에 한참 못 미친다. */
    private static final Duration SKIPPED_CALL_LIMIT = Duration.ofMillis(100);

    /** 한 번의 호출이 얼린 Redis 앞에서 쓸 수 있는 시간 — 명령 시간 상한 500ms 에 여유를 더한다. */
    private static final Duration BOUNDED_CALL_LIMIT = Duration.ofMillis(1500);

    /** 서킷이 열린 뒤 다시 시험 호출을 허용하기까지({@code wait-duration-in-open-state}) 에 여유를 더한 복귀 상한. */
    private static final Duration RECOVERY_LIMIT = Duration.ofSeconds(8);

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    private void withTemplate(Consumer<Fixture> body) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
                .withUserConfiguration(RedisClientConfig.class, RedisGuardConfig.class)
                .withPropertyValues("spring.data.redis.host=" + redis.host(), "spring.data.redis.port=" + redis.port())
                .run(context -> body.accept(new Fixture(context.getBean(StringRedisTemplate.class),
                        context.getBean(CircuitBreakerRegistry.class).circuitBreaker("redis"))));
    }

    private record Fixture(StringRedisTemplate template, CircuitBreaker breaker) {

        Duration timeOf(Runnable call) {
            long start = System.nanoTime();
            try {
                call.run();
            } catch (DataAccessException ignored) {
                // 실패 시간을 재는 것이 목적이다
            }
            return Duration.ofNanos(System.nanoTime() - start);
        }

        void read() {
            template.opsForValue().get("outage:probe");
        }
    }

    @Test
    @DisplayName("얼린 Redis 앞에서 한 번의 호출은 명령 시간 상한 안에 끝나고, 실패가 이어지면 서킷이 열려 Redis 를 부르지 않고 곧바로 실패한다")
    void 얼린_Redis_앞에서_서킷이_열려_호출이_즉시_실패한다() {
        withTemplate(fixture -> {
            fixture.read();
            redis.pause();
            try {
                for (int i = 0; i < CALLS_TO_OPEN; i++) {
                    assertThat(fixture.timeOf(fixture::read)).as("얼린 Redis 앞에서 %d번째 호출".formatted(i + 1))
                            .isLessThan(BOUNDED_CALL_LIMIT);
                }

                assertThat(fixture.breaker().getState()).as("실패 %d번 뒤 서킷".formatted(CALLS_TO_OPEN))
                        .isEqualTo(CircuitBreaker.State.OPEN);
                assertThat(fixture.timeOf(fixture::read)).as("열린 서킷 앞에서의 호출 시간").isLessThan(SKIPPED_CALL_LIMIT);
                assertThatThrownBy(fixture::read).as("열린 서킷의 거절은 호출부가 이미 잡는 Redis 예외다")
                        .isInstanceOf(DataAccessException.class);
            } finally {
                redis.unpause();
            }
        });
    }

    @Test
    @DisplayName("얼렸던 Redis 가 다시 응답하면 서킷이 대기 시간 뒤 스스로 닫혀 Redis 경로로 돌아온다")
    void 얼렸던_Redis_가_풀리면_서킷이_닫히고_Redis_경로로_돌아온다() throws InterruptedException {
        withTemplate(fixture -> {
            fixture.read();
            redis.pause();
            for (int i = 0; i < CALLS_TO_OPEN; i++) {
                fixture.timeOf(fixture::read);
            }
            assertThat(fixture.breaker().getState()).as("복귀를 보기 전에 서킷이 열려 있어야 한다").isEqualTo(CircuitBreaker.State.OPEN);
            redis.unpause();

            long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
            while (fixture.breaker().getState() != CircuitBreaker.State.CLOSED && System.nanoTime() < deadline) {
                fixture.timeOf(fixture::read);
                sleep(200);
            }

            assertThat(fixture.breaker().getState()).as("Redis 가 다시 응답한 뒤 %s 안에".formatted(RECOVERY_LIMIT))
                    .isEqualTo(CircuitBreaker.State.CLOSED);
            fixture.template().opsForValue().set("outage:back", "1");
            assertThat(fixture.template().opsForValue().get("outage:back")).as("닫힌 서킷으로 실제 Redis 에 기록됨").isEqualTo("1");
        });
    }

    @Test
    @DisplayName("멈춘(연결 거부) Redis 에서도 실패가 이어지면 서킷이 열리고, 다시 켜면 닫힌다")
    void 멈춘_Redis_에서도_서킷이_열렸다_복귀한다() {
        withTemplate(fixture -> {
            fixture.read();
            redis.stop();
            try {
                for (int i = 0; i < CALLS_TO_OPEN; i++) {
                    assertThat(fixture.timeOf(fixture::read)).as("멈춘 Redis 앞에서 %d번째 호출".formatted(i + 1))
                            .isLessThan(BOUNDED_CALL_LIMIT);
                }
                assertThat(fixture.breaker().getState()).as("실패 %d번 뒤 서킷".formatted(CALLS_TO_OPEN))
                        .isEqualTo(CircuitBreaker.State.OPEN);
                assertThat(fixture.timeOf(fixture::read)).as("열린 서킷 앞에서의 호출 시간").isLessThan(SKIPPED_CALL_LIMIT);
            } finally {
                redis.restart();
            }

            long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
            while (fixture.breaker().getState() != CircuitBreaker.State.CLOSED && System.nanoTime() < deadline) {
                fixture.timeOf(fixture::read);
                sleep(200);
            }
            assertThat(fixture.breaker().getState()).as("Redis 를 다시 켠 뒤 %s 안에".formatted(RECOVERY_LIMIT))
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
