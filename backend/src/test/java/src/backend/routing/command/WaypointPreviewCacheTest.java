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

    /**
     * BR-365 — 정리가 <b>오늘 것까지</b> 지우지 않는다. 시험이 정리 뒤에 넣는 항목만 보면 조건이 "오늘 이전" 에서 "오늘 이하" 로 어긋나도 통과한다 —
     * 정리 시점에 <b>이미 들어 있는 다른 오늘 회차</b>가 살아남아야 한다. 이 보관소는 회차 전체가 공유해, 정리가 오늘 회차의 배포 대기
     * 미리보기를 지우면 그 관리자의 배포가 {@code 409 PREVIEW_STALE} 이 된다.
     */
    @Test
    void 새_미리보기가_들어와도_이미_있는_오늘_회차_미리보기는_남는다() {
        WaypointPreviewCache cache = new WaypointPreviewCache(CLOCK);
        cache.put(3L, TODAY, previewOf("today-waiting"));
        cache.put(4L, TODAY.plusDays(1), previewOf("tomorrow-waiting"));
        cache.put(1L, TODAY.minusDays(1), previewOf("yesterday"));

        cache.put(2L, TODAY, previewOf("today"));

        assertThat(cache.find(1L)).as("어제 회차는 정리된다").isEmpty();
        assertThat(cache.find(3L)).as("정리 시점에 이미 있던 오늘 회차는 남는다").isPresent();
        assertThat(cache.find(4L)).as("내일 회차도 남는다").isPresent();
        assertThat(cache.find(2L)).isPresent();
    }

    private static WaypointPreview previewOf(String token) {
        return new WaypointPreview(token, 1L, false, 1, "fp", 1L, null);
    }
}
