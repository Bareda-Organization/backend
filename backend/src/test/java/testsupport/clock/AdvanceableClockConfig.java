package testsupport.clock;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 앱 전체의 {@link Clock} 을 {@link AdvanceableClock} 으로 바꾼다 — {@code @Import} 한 시험이 시각을 앞으로 옮길 수 있다. */
@TestConfiguration
public class AdvanceableClockConfig {

    @Bean
    @Primary
    AdvanceableClock advanceableClock() {
        return new AdvanceableClock(Clock.system(ZoneId.of("Asia/Seoul")));
    }
}
