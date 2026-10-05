package src.backend.global.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각이 필요한 모든 곳이 시스템 시계 대신 주입받는 기준 Clock — 기준 시간대는 Asia/Seoul(ERD §2).
 * 테스트는 이 빈을 고정 Clock 으로 교체해 시각 의존 로직을 결정론적으로 검증한다.
 */
@Configuration
public class ClockConfig {

    /** Asia/Seoul 기준 시스템 Clock 빈(마이크로초 단위) — 테스트는 이 빈을 고정 Clock 으로 교체해 대체한다. */
    @Bean
    public Clock clock() {
        return toDbPrecision(Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /**
     * DB(timestamptz)가 저장하는 마이크로초까지만 내는 시계 — Linux JVM 은 나노초라, 그대로 두면 응답에 실은 시각과 저장 뒤 다시 읽은
     * 시각이 반올림만큼 어긋난다(같은 client_key 재요청의 {@code changed_at} 이 1µs 다르던 결함).
     */
    static Clock toDbPrecision(Clock base) {
        return Clock.tick(base, Duration.ofNanos(1_000));
    }
}
