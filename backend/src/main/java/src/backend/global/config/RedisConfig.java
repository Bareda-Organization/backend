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
 * <p>옛 좌표 DTO 2종(위치 도메인)이 삭제되며 허용 목록이 지금은 비어 있다 —
 * 새 좌표·이벤트 DTO 가 이 저장소에 값으로 들어갈 때 {@code allowIfSubType} 으로 추가한다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
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
