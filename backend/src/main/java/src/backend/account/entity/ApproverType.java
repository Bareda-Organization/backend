package src.backend.account.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 가입 승인 주체 구분 2종 — {@code signup_request.approver_type}(CHECK 로 강제)의 값 도메인이다.
 */
public enum ApproverType {

    /** 학원 관계자(메인 관리자)가 승인. */
    STAFF,
    /** 플랫폼 관리자가 승인. */
    SYSTEM_ADMIN;

    /** {@link ApproverType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ApproverType> {
        public Db() {
            super(ApproverType.class);
        }
    }
}
