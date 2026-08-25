package src.backend.boarding.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 탑승자 상태 변경 주체 구분 2종 — {@code rider_status_history.actor_type}(CHECK 로 강제)의
 * 값 도메인이다.
 */
public enum ActorType {

    /** 동승자가 직접 변경. */
    ESCORT,
    /** 시스템(배치·타임아웃 등)이 변경. */
    SYSTEM;

    /** {@link ActorType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ActorType> {
        public Db() {
            super(ActorType.class);
        }
    }
}
