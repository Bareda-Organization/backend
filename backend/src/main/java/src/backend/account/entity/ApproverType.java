package src.backend.account.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 가입 승인 주체 구분 2종 — {@code signup_request.approver_type}(CHECK 로 강제)의 값 도메인이다.
 */
public enum ApproverType {

    /** 그 학원의 관계자(학원 소속 {@code Role.STAFF} 계정)가 승인 — 학생·학부모·기사·매니저 가입 대상. */
    STAFF,
    /** 메인 관리자(운영사 {@code SYSTEM_ADMIN})가 승인 — 학원 관계자 가입 대상(FEATURE_SPEC §3.1). */
    SYSTEM_ADMIN;

    /** {@link ApproverType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ApproverType> {
        public Db() {
            super(ApproverType.class);
        }
    }
}
