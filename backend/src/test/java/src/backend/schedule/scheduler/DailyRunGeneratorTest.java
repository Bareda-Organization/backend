package src.backend.schedule.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        new DailyRunGenerator(service, clock).generateTodayAndTomorrow();

        verify(service).generate(LocalDate.of(2026, 9, 25));
    }

    @Test
    void 같은_실행이_내일_회차도_만든다() {
        RunGenerationService service = mock(RunGenerationService.class);
        // KST 2026-09-25 00:05 → 내일은 2026-09-26. 학부모 "특정 날짜" 변경 신청이 전날에 걸리려면 내일 회차가 있어야 한다(Ruling 366).
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));

        new DailyRunGenerator(service, clock).generateTodayAndTomorrow();

        verify(service).generate(LocalDate.of(2026, 9, 26));
    }

    /**
     * BR-241 — 오늘·내일이 모두 실패하면 <b>오늘의 예외</b>가 올라가고 내일 예외는 suppressed 로 남는다. {@code finally} 안의
     * 예외가 {@code try} 의 예외를 대체하면 호출부(기동 보충·스케줄러·지표)가 내일 예외만 보고 오늘 실패를 잃는다.
     */
    @Test
    void 오늘과_내일이_모두_실패하면_오늘_예외를_던지고_내일_예외를_덧붙인다() {
        RunGenerationService service = mock(RunGenerationService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        IllegalStateException today = new IllegalStateException("오늘 실패");
        IllegalStateException tomorrow = new IllegalStateException("내일 실패");
        when(service.generate(LocalDate.of(2026, 9, 25))).thenThrow(today);
        when(service.generate(LocalDate.of(2026, 9, 26))).thenThrow(tomorrow);

        assertThatThrownBy(() -> new DailyRunGenerator(service, clock).generateTodayAndTomorrow())
                .isSameAs(today)
                .hasSuppressedException(tomorrow);
    }

    /** BR-241 — 오늘만 실패해도 내일 생성은 시도하고, 던지는 것은 오늘 예외다(기존 동작 유지). */
    @Test
    void 오늘만_실패해도_내일은_만들고_오늘_예외를_던진다() {
        RunGenerationService service = mock(RunGenerationService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        IllegalStateException today = new IllegalStateException("오늘 실패");
        when(service.generate(LocalDate.of(2026, 9, 25))).thenThrow(today);

        assertThatThrownBy(() -> new DailyRunGenerator(service, clock).generateTodayAndTomorrow()).isSameAs(today);
        verify(service).generate(LocalDate.of(2026, 9, 26));
    }
}
