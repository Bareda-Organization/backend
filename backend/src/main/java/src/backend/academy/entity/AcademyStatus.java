package src.backend.academy.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/** 학원 상태 2종 — {@code academy.status} CHECK. 비활성화해도 기존 로그인은 유지된다(ACAD-04). */
public enum AcademyStatus {

    ACTIVE, INACTIVE;

    /** {@link AcademyStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<AcademyStatus> {
        public Db() {
            super(AcademyStatus.class);
        }
    }
}
