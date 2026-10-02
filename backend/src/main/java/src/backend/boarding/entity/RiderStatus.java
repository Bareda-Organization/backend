package src.backend.boarding.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 회차 내 탑승자 개별 상태 5종 — {@code run_rider.status}(CHECK 로 강제)의 값 도메인이다.
 *
 * <p>{@code rider_status_history.from_status}·{@code to_status} 도 같은 값 도메인을 쓰고 같은 CHECK 로
 * 강제된다(R46 A-5) — 읽을 때는 {@link RiderStatus.Db#convertToEntityAttribute} 의 {@link Enum#valueOf} 가
 * 한 번 더 막는다.
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
