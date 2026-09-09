package src.backend.boarding.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 회차 내 탑승자 개별 상태 5종 — {@code run_rider.status}(CHECK 로 강제)의 값 도메인이다.
 *
 * <p>{@code rider_status_history.from_status}·{@code to_status} 도 같은 값 도메인을 쓰지만
 * CHECK 가 없다 — 스키마가 값을 보장하지 않으므로 잘못된 값은 쓸 때가 아니라 그 행을 다시 읽어
 * {@link RiderStatus.Db#convertToEntityAttribute} 를 타는 순간 {@link Enum#valueOf} 에서 터진다.
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
