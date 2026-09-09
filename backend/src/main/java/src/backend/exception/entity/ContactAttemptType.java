package src.backend.exception.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 미승차 연락 시도 수단 2종 — {@code no_show_contact_attempt.attempt_type}(CHECK 로 강제)의
 * 값 도메인이다.
 */
public enum ContactAttemptType {

    /** 전화. */
    CALL,
    /** 메시지(문자·알림톡 등). */
    MESSAGE;

    /** {@link ContactAttemptType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ContactAttemptType> {
        public Db() {
            super(ContactAttemptType.class);
        }
    }
}
