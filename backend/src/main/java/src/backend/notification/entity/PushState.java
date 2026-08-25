package src.backend.notification.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 푸시 발송 상태 4종 — {@code notification_log.push_state}(CHECK 로 강제)의 값 도메인이다.
 */
public enum PushState {

    /** 발송 대기. */
    PENDING,
    /** 발송됨. */
    SENT,
    /** 발송 실패. */
    FAILED,
    /** 설정에 의해 건너뜀. */
    SKIPPED;

    /** {@link PushState} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<PushState> {
        public Db() {
            super(PushState.class);
        }
    }
}
