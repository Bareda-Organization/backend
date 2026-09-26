package testsupport.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시드 회차의 {@code service_date} 를 실제로 읽어 그 날짜로 {@link Clock} 을 이동시킨다 — 값이 같은
 * 시험 3개가 각자 중첩 클래스로 선언하던 것을 공유 설정 하나로 모았다(BR-107, {@code
 * FixedClockXXXConfig} 계열과 같은 이유).
 */
@TestConfiguration
public class SeedDateClockConfig {

    @Bean
    @Primary
    Clock seedDateClock(JdbcTemplate jdbcTemplate) {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock base = Clock.system(seoul);
        LocalDate seedDate = jdbcTemplate.queryForObject("SELECT MIN(service_date) FROM run", LocalDate.class);
        long offsetDays = ChronoUnit.DAYS.between(LocalDate.now(base), seedDate);
        return Clock.offset(base, Duration.ofDays(offsetDays));
    }
}
