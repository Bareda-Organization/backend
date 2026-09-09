package src.backend.account.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 인증번호 발송 목적 2종 — {@code verification_code.purpose}(CHECK 로 강제)의 값 도메인이다.
 */
public enum VerificationPurpose {

    /** 아이디 찾기. */
    LOGIN_ID,
    /** 비밀번호 재설정. */
    PASSWORD;

    /** {@link VerificationPurpose} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<VerificationPurpose> {
        public Db() {
            super(VerificationPurpose.class);
        }
    }
}
