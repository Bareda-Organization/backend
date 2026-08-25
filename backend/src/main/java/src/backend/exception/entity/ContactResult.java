package src.backend.exception.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 미승차 연락 결과 2종 — {@code no_show_contact_attempt.result}(CHECK 로 강제)의 값 도메인이다.
 */
public enum ContactResult {

    /** 응답함. */
    ANSWERED,
    /** 무응답. */
    NO_ANSWER;

    /** {@link ContactResult} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ContactResult> {
        public Db() {
            super(ContactResult.class);
        }
    }
}
