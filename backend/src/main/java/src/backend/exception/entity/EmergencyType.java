package src.backend.exception.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 비상 신고 종류 4종 — {@code emergency_alert.type}(CHECK 로 강제)의 값 도메인이다.
 * {@code ETC} 로 신고할 때는 {@code memo} 필수가 별도 CHECK 로 함께 강제된다.
 */
public enum EmergencyType {

    /** 사고. */
    ACCIDENT,
    /** 차량 고장. */
    VEHICLE_FAULT,
    /** 학생 응급 상황. */
    STUDENT_EMERGENCY,
    /** 기타. */
    ETC;

    /** {@link EmergencyType} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<EmergencyType> {
        public Db() {
            super(EmergencyType.class);
        }
    }
}
