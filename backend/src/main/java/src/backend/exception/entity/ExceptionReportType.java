package src.backend.exception.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 현장 예외 보고 종류 4종 — {@code exception_report.type}(CHECK 로 강제)의 값 도메인이다.
 * {@code GUARDIAN_ABSENT} 로 보고할 때는 {@code run_rider_id} 필수가 별도 CHECK 로 함께 강제된다.
 */
public enum ExceptionReportType {

    /** 보호자 부재. */
    GUARDIAN_ABSENT,
    /** 도로 통제. */
    ROAD_BLOCK,
    /** 차량 문제. */
    VEHICLE_ISSUE,
    /** 기타. */
    ETC;

    /** {@link ExceptionReportType} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ExceptionReportType> {
        public Db() {
            super(ExceptionReportType.class);
        }
    }
}
