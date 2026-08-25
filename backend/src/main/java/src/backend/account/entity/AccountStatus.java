package src.backend.account.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 계정 상태 4종 — {@code account.status}(CHECK 로 강제)의 값 도메인이다.
 */
public enum AccountStatus {

    /** 승인 대기. */
    PENDING,
    /** 정상 사용. */
    ACTIVE,
    /** 가입 거절됨. */
    REJECTED,
    /** 반복 실패 등으로 차단됨. */
    BLOCKED;

    /** {@link AccountStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<AccountStatus> {
        public Db() {
            super(AccountStatus.class);
        }
    }
}
