package src.backend.account.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 가입 승인 요청 상태 3종 — {@code signup_request.status}(CHECK 로 강제)의 값 도메인이다.
 */
public enum SignupRequestStatus {

    /** 승인 대기. */
    PENDING,
    /** 승인됨. */
    ACCEPTED,
    /** 거절됨. */
    REJECTED;

    /** {@link SignupRequestStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<SignupRequestStatus> {
        public Db() {
            super(SignupRequestStatus.class);
        }
    }
}
