package src.backend.schedule.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import src.backend.global.config.ClockConfig;
import src.backend.schedule.command.RunGenerationService;

/**
 * "오늘이 며칠인가" 를 정하는 경로(BR-106) — 운영 시계의 시간대와 배치가 고르는 날짜. 시간 의존 시험 대부분이
 * 고정 시계로 운영 {@code Clock} 을 덮고 {@code generate(날짜)} 를 직접 불러, 이 둘을 보는 시험이 없었다.
 */
class DailyRunGeneratorTest {

    @Test
    void 운영_시계는_서비스_기준_시간대다() {
        assertThat(new ClockConfig().clock().getZone())
                .as("UTC 면 KST 00:05 cron 이 전날을 만들려다 전부 중복으로 건너뛴다").isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void KST_자정_직후_실행은_그날_KST_날짜의_회차를_만든다() {
        RunGenerationService service = mock(RunGenerationService.class);
        // KST 2026-09-25 00:05 = UTC 2026-09-24 15:05
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));

        new DailyRunGenerator(service, clock).generateToday();

        verify(service).generate(LocalDate.of(2026, 9, 25));
    }
}
