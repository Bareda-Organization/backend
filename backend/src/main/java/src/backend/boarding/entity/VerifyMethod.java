package src.backend.boarding.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 탑승자 상태 변경 확인 수단 2종 — {@code rider_status_history.verify_method}(CHECK 로 강제)의
 * 값 도메인이다.
 */
public enum VerifyMethod {

    /** 사진 촬영으로 확인. */
    PHOTO,
    /** 동승자 수동 확인. */
    MANUAL;

    /** {@link VerifyMethod} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<VerifyMethod> {
        public Db() {
            super(VerifyMethod.class);
        }
    }
}
