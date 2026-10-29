package testsupport.clock;

import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 앱 전체의 {@link java.time.Clock} 을 {@link SettableClock}(초기값은 {@code FixedClock20300401Config} 와 같은 정오 KST)으로 바꾼다 — {@code @Import} 한 시험이 시각을 직접 놓는다. */
@TestConfiguration
public class SettableClockConfig {

    @Bean
    @Primary
    SettableClock settableClock() {
        return new SettableClock(Instant.parse("2030-04-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    }
}
