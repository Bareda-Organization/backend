package src.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * RedisConnectionFactory 는 spring-boot-starter-data-redis 가 spring.data.redis.* 설정으로
 * 이미 자동 구성한다 — 여기서는 값 직렬화만 JDK 기본(바이너리)에서 JSON 으로 바꿔,
 * Kafka 이벤트 페이로드와 형식을 맞추고 redis-cli 로 직접 조회·디버깅할 수 있게 한다.
 *
 * <p>이 프로젝트는 Spring Boot 4 기본 Jackson 3({@code tools.jackson.databind}) 를 쓴다 —
 * 그래서 구버전 Jackson 2 기반 {@code GenericJackson2JsonRedisSerializer} 대신 Jackson 3 기반
 * {@link GenericJacksonJsonRedisSerializer}를 쓴다. Jackson 3 는 {@code java.time.*} 타입을
 * 별도 모듈 등록 없이 기본 지원해 {@code OffsetDateTime} 필드도 그대로 직렬화된다.
 * 값 타입 정보(@class)를 함께 저장해 조회 시 원래 도메인 타입으로 복원하되,
 * {@link PolymorphicTypeValidator} 로 역직렬화 가능한 타입을 화이트리스트로만 허용한다 — 저장 값에
 * 심긴 {@code @class} 문자열이 임의 타입을 만들 수 있는 다형 역직렬화 취약점을 차단한다.
 *
 * <p>{@code src.backend.location.dto} 패키지 전체를 허용한다(목표 3, Phase 10 T1) — 최신 좌표 값
 * ({@code RunPositionRedisValue})이 이 패키지에 산다. 패키지 단위로 허용해, 뒤이어 이 도메인이
 * Redis 에 값을 더 얹어도(예: LOC-02·LOC-03) 매번 이 파일을 다시 고치지 않게 한다.
 *
 * <p>{@code java.math.} 도 함께 허용한다 — {@code BigDecimal} 필드({@code lat}·{@code lng})는
 * {@code final} 이라도 기본 타입 지정(default typing) 제외 대상이 아니다. 평범한 JSON 숫자로만 적으면
 * 역직렬화 시 {@code Double} 로 복원돼 정밀도가 달라질 수 있어, Jackson 이 이 타입만은
 * {@code ["java.math.BigDecimal", 37.5]} 형태로 타입 정보를 함께 적기 때문이다(실측 확인 —
 * {@code src.backend.location.dto} 만 허용했을 때 Redis 조회가
 * {@code InvalidTypeIdException: Could not resolve type id 'java.math.BigDecimal'} 로 실패했다).
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("src.backend.location.dto.")
                .allowIfSubType("java.math.")
                .build();

        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
