package testsupport.redis;

import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * {@link RedisTestContainerBase} 를 상속하지 않은 시험도 전부 전용 Redis 컨테이너로 돌리는
 * 전역 장치(목표 10, 2026-09-14) — 상속을 요구하면 "새 시험 클래스를 만들 때 잊는다" 는 경로가
 * 남는데, 그 경로가 실제로 141개 시험을 공유 Redis(운영 포트 16379)로 가리키게 뒀다.
 *
 * <p>이 클래스는 {@code META-INF/spring.factories} 로 등록돼 Spring TestContext 프레임워크가
 * {@code @SpringBootTest}·{@code @WebMvcTest}·{@code @DataJpaTest} 등 컨텍스트를 띄우는 시험마다
 * 자동으로 호출한다 — 시험 클래스가 이 사실을 알거나 무엇을 상속할 필요가 없다. {@code
 * @DynamicPropertySource} 자체가 내부적으로 같은 확장점({@code
 * DynamicPropertiesContextCustomizerFactory}, spring-test 의 {@code META-INF/spring.factories})으로
 * 동작하는 것과 같은 방식이라 새 메커니즘이 아니라 같은 메커니즘을 전역으로 넓힌 것이다.
 *
 * <p>컨테이너 인스턴스는 새로 만들지 않고 {@link RedisTestContainerBase#REDIS} 를 그대로 쓴다 —
 * 하나의 JVM 에 컨테이너가 두 개 뜨는 낭비를 막고, 그 클래스 자바독이 이미 기록한 "클래스 단위
 * 컨테이너 수명 관리 금지" 제약을 그대로 물려받는다. {@code RedisTestContainerBase} 를 상속한
 * 시험은 자기 {@code @DynamicPropertySource} 와 이 전역 장치가 같은 값을 중복으로 등록하게
 * 되는데, 값이 항상 같아(같은 컨테이너) 충돌이 아니라 약간의 중복 계산일 뿐이다.
 *
 * <p>DB(-{@code PtestDbUrl}, build.gradle)는 인자를 요구하고 인자 부재 시 실패하는 쪽을 택했는데
 * Redis 는 왜 반대(자동 주입)를 택했는가 — DB 격리는 **좌석마다 다른 이름**이 필요해 사람이
 * 그 이름을 대야 하지만, Redis 는 **어느 시험이 오든 같은 전용 컨테이너 하나**면 충분하다(키
 * 접두사로 시험 간 충돌을 가르는 설계는 이미 {@link RedisTestContainerBase} 자바독에 있다).
 * 대는 이름이 없으니 실패시킬 이유도 없고, 자동 주입이 유일하게 "잊어도 안전한" 형태다.
 */
public class RedisTestContainerContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new RedisIsolationContextCustomizer();
    }

    /**
     * 컨텍스트 리프레시 전에 {@code spring.data.redis.host}·{@code port} 를 전용 컨테이너 값으로
     * 덮어쓴다. {@code equals}·{@code hashCode} 를 상수로 고정한 이유는 {@link ContextCustomizer}
     * 가 {@link MergedContextConfiguration} 의 캐시 키에 들어가기 때문이다(자바독 경고 참고) —
     * 매번 새 인스턴스를 만들어도 값이 항상 같은 컨테이너를 가리키므로 "같다" 로 답해야
     * 컨텍스트 캐시가 시험 클래스마다 새로 뜨는 것을 막는다.
     */
    private static final class RedisIsolationContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "redisTestContainerIsolation",
                    Map.of(
                            "spring.data.redis.host", RedisTestContainerBase.REDIS.getHost(),
                            "spring.data.redis.port", RedisTestContainerBase.REDIS.getMappedPort(6379))));
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof RedisIsolationContextCustomizer;
        }

        @Override
        public int hashCode() {
            return RedisIsolationContextCustomizer.class.hashCode();
        }
    }
}
