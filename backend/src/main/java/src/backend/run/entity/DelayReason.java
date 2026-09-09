package src.backend.run.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 지연 사유 4종(NTF-06, API_SPEC §4.9) — {@code delay_notice.reason}(CHECK 로 강제)의 값 도메인이다.
 */
public enum DelayReason {

    /** 교통 정체. */
    TRAFFIC,
    /** 기상. */
    WEATHER,
    /** 차량 점검. */
    VEHICLE_CHECK,
    /** 이전 승하차지 대기. */
    PREV_STOP_WAIT;

    /** {@link DelayReason} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<DelayReason> {
        public Db() {
            super(DelayReason.class);
        }
    }
}
