package src.backend.global.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각이 필요한 모든 곳이 시스템 시계 대신 주입받는 기준 Clock — 기준 시간대는 Asia/Seoul(ERD §2).
 * 테스트는 이 빈을 고정 Clock 으로 교체해 시각 의존 로직을 결정론적으로 검증한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
