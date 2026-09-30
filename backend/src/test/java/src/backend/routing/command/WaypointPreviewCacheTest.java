package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;

/** 경유 지점 미리보기 보관소의 지난 회차 정리(R36-BE 목표 9) — 새 미리보기를 넣을 때 운행일이 오늘보다 앞선 것을 지운다. */
class WaypointPreviewCacheTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** KST 2026-09-30 10:00. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), SEOUL);

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Test
    void 오늘_회차_미리보기를_넣으면_어제_회차_것만_지워진다() {
        WaypointPreviewCache cache = new WaypointPreviewCache(CLOCK);
        cache.put(1L, TODAY.minusDays(1), previewOf("yesterday"));

        cache.put(2L, TODAY, previewOf("today"));

        assertThat(cache.find(1L)).as("지난 날짜 회차 미리보기는 배포되지 않아도 새 미리보기가 들어올 때 정리된다").isEmpty();
        assertThat(cache.find(2L)).as("오늘 회차 미리보기는 그대로").isPresent();
    }

    private static WaypointPreview previewOf(String token) {
        return new WaypointPreview(token, 1L, false, 1, "fp", 1L, null);
    }
}
