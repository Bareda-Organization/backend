package src.backend.audit.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 감사 이력 세부 동작 7종 — {@code audit_log.action}(CHECK 로 강제)의 값 도메인이다.
 */
public enum AuditAction {

    /** 조회. */
    READ,
    /** 수정. */
    UPDATE,
    /** 삭제. */
    DELETE,
    /** 로그인 성공. */
    LOGIN_SUCCESS,
    /** 로그인 실패. */
    LOGIN_FAIL,
    /** 계정 차단. */
    BLOCK,
    /** 차단 해제. */
    UNBLOCK;

    /** {@link AuditAction} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<AuditAction> {
        public Db() {
            super(AuditAction.class);
        }
    }
}
