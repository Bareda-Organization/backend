package src.backend.student.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 학생 성별 2종 — {@code student.gender}(CHECK 로 강제)의 값 도메인이다.
 */
public enum Gender {

    MALE, FEMALE;

    /** {@link Gender} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<Gender> {
        public Db() {
            super(Gender.class);
        }
    }
}
