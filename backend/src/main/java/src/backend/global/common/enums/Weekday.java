package src.backend.global.common.enums;

import java.time.LocalDate;
import java.util.Locale;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 요일 7종 — {@code weekly_address} · {@code schedule} · {@code route} 세 테이블의 CHECK 가 공유하는
 * 값 도메인이다.
 */
public enum Weekday {

    MON, // 월요일
    TUE, // 화요일
    WED, // 수요일
    THU, // 목요일
    FRI, // 금요일
    SAT, // 토요일
    SUN; // 일요일

    /**
     * 그 날짜의 요일을 이 값 공간(3자리 대문자)으로 옮긴다(BR-099, 요일 변환 중복 통합). {@code LocalDate}
     * 자체가 요일을 들고 있으므로 시계를 보지 않는다 — 시각(예: {@code OffsetDateTime})에서 시작하는
     * 호출부는 먼저 자신의 기준 시간대로 {@code LocalDate} 를 뽑은 뒤 이 메서드를 부른다.
     */
    public static Weekday of(LocalDate date) {
        return valueOf(date.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    /** {@link Weekday} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<Weekday> {
        public Db() {
            super(Weekday.class);
        }
    }
}
