package src.backend.global.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * {@link Weekday#of(LocalDate)} 경계값 — 요일 변환 중복 통합(BR-099)의 유일한 새 코드라 RED 대상이다.
 * 월·일요일(주의 양끝)과 연말(2026-12-31, 12-31 → 목요일)을 확인한다.
 */
class WeekdayTest {

    @Test
    void 월요일을_MON으로_옮긴다() {
        assertThat(Weekday.of(LocalDate.of(2026, 9, 21))).isEqualTo(Weekday.MON);
    }

    @Test
    void 일요일을_SUN으로_옮긴다() {
        assertThat(Weekday.of(LocalDate.of(2026, 9, 27))).isEqualTo(Weekday.SUN);
    }

    @Test
    void 연말_날짜도_옳게_옮긴다() {
        assertThat(Weekday.of(LocalDate.of(2026, 12, 31))).isEqualTo(Weekday.THU);
    }
}
