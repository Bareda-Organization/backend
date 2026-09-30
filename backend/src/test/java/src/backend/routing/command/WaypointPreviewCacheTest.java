package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;

/** 경유 지점 미리보기 보관소의 지난 회차 정리(R36-BE 목표 9) — 운행일이 오늘보다 앞선 미리보기만 지운다. */
class WaypointPreviewCacheTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Test
    void 어제_회차_미리보기만_지워지고_오늘_회차는_남는다() {
        WaypointPreviewCache cache = new WaypointPreviewCache();
        cache.put(1L, TODAY.minusDays(1), previewOf("yesterday"));
        cache.put(2L, TODAY, previewOf("today"));

        cache.evictBefore(TODAY);

        assertThat(cache.find(1L)).as("지난 날짜 회차 미리보기는 배포되지 않아도 정리된다").isEmpty();
        assertThat(cache.find(2L)).as("오늘 회차 미리보기는 그대로").isPresent();
    }

    private static WaypointPreview previewOf(String token) {
        return new WaypointPreview(token, 1L, false, 1, "fp", 1L, null);
    }
}
