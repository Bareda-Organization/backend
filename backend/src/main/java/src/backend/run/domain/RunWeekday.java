package src.backend.run.domain;

import java.time.LocalDate;
import java.util.Locale;

import src.backend.global.common.enums.Weekday;

/**
 * 날짜 → {@link Weekday} 변환 — {@code route}·{@code schedule} 의 {@code weekday} CHECK 가 쓰는
 * 값 공간으로 옮긴다(BR-101, 2026-09-25 검사 — {@code weekdayOf} 5벌 중복 통합).
 *
 * <p>{@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다. 시계가 필요한 것은 "오늘이
 * 며칠인가" 뿐이고 그 판정은 이 클래스 밖(호출부)에 있다 — 섞으면 지난 날짜를 다시 계산할 때 오늘
 * 요일이 끼어든다.
 */
public final class RunWeekday {

    private RunWeekday() {
    }

    /** 그 날짜의 요일을 {@code route.weekday} 값 공간(3자리 대문자)으로 옮긴다. */
    public static Weekday of(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
