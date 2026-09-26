package testsupport.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 고정 시계 {@code 2033-04-01T03:00:00Z}(2033-04-01 12:00 KST) — 값이 같은 시험 클래스가 각자 중첩
 * {@code FixedClockConfig} 로 선언하던 것을 공유 설정 하나로 모았다(BR-107). 나눠 선언하면 값이
 * 같아도 선언 클래스(캐시 키의 일부)가 갈려 {@code @SpringBootTest} 컨텍스트가 중복 기동된다 —
 * {@code @Import(FixedClock20330401Config.class)} 로 이 클래스를 공유하면 캐시가 합쳐진다.
 */
@TestConfiguration
public class FixedClock20330401Config {

    private static final Instant FIXED = Instant.parse("2033-04-01T03:00:00Z");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
    }
}
