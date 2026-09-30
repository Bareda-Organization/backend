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
import src.backend.routing.command.WaypointPreviewCache;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
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

        new DailyRunGenerator(service, new WaypointPreviewCache(), clock).generateTodayAndTomorrow();

        verify(service).generate(LocalDate.of(2026, 9, 25));
    }

    @Test
    void 같은_실행이_내일_회차도_만든다() {
        RunGenerationService service = mock(RunGenerationService.class);
        // KST 2026-09-25 00:05 → 내일은 2026-09-26. 학부모 "특정 날짜" 변경 신청이 전날에 걸리려면 내일 회차가 있어야 한다(Ruling 366).
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));

        new DailyRunGenerator(service, new WaypointPreviewCache(), clock).generateTodayAndTomorrow();

        verify(service).generate(LocalDate.of(2026, 9, 26));
    }

    @Test
    void 같은_실행이_지난_날짜_회차의_경유_지점_미리보기를_정리한다() {
        RunGenerationService service = mock(RunGenerationService.class);
        WaypointPreviewCache previewCache = new WaypointPreviewCache();
        // KST 2026-09-25 00:05 — 어제(09-24) 회차 미리보기는 지워지고 오늘(09-25) 것은 남는다(R36-BE 목표 9).
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        previewCache.put(1L, LocalDate.of(2026, 9, 24), new WaypointPreview("y", 1L, false, 1, "fp", 1L, null));
        previewCache.put(2L, LocalDate.of(2026, 9, 25), new WaypointPreview("t", 1L, false, 1, "fp", 1L, null));

        new DailyRunGenerator(service, previewCache, clock).generateTodayAndTomorrow();

        assertThat(previewCache.find(1L)).isEmpty();
        assertThat(previewCache.find(2L)).isPresent();
    }
}
