package src.backend.global.common.enums;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 등하원 방향 2종 — {@code weekly_address} · {@code schedule} · {@code route} · {@code run} 네
 * 테이블의 CHECK 가 공유하는 값 도메인이다.
 */
public enum Direction {

    /** 등원. */
    TO_ACADEMY,
    /** 하원. */
    FROM_ACADEMY;

    /** {@link Direction} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<Direction> {
        public Db() {
            super(Direction.class);
        }
    }
}
