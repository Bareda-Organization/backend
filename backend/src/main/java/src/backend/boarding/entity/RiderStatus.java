package src.backend.boarding.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 회차 내 탑승자 개별 상태 5종 — {@code run_rider.status}(CHECK 로 강제)의 값 도메인이다.
 */
public enum RiderStatus {

    /** 대기 중. */
    WAITING,
    /** 탑승함. */
    BOARDED,
    /** 하차함. */
    ALIGHTED,
    /** 결석 처리됨. */
    ABSENT,
    /** 미승차 처리됨. */
    NO_SHOW;

    /** {@link RiderStatus} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<RiderStatus> {
        public Db() {
            super(RiderStatus.class);
        }
    }
}
