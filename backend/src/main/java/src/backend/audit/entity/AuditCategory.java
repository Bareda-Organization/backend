package src.backend.audit.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 감사 이력 대분류 2종 — {@code audit_log.category}(CHECK 로 강제)의 값 도메인이다.
 */
public enum AuditCategory {

    /** 개인정보 조회·수정 이력. */
    DATA_ACCESS,
    /** 로그인·차단 이력. */
    LOGIN;

    /** {@link AuditCategory} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<AuditCategory> {
        public Db() {
            super(AuditCategory.class);
        }
    }
}
