package src.backend.global.common.enums;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 요일 7종 — {@code weekly_address} · {@code schedule} · {@code route} 세 테이블의 CHECK 가 공유하는
 * 값 도메인이다.
 */
public enum Weekday {

    MON, TUE, WED, THU, FRI, SAT, SUN;

    /** {@link Weekday} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<Weekday> {
        public Db() {
            super(Weekday.class);
        }
    }
}
