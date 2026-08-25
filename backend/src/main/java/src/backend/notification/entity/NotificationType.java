package src.backend.notification.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 알림 종류 18종 — {@code notification_log.type}(CHECK 로 강제, API_SPEC §9.7 과 값 일치 확인)의
 * 값 도메인이다.
 */
public enum NotificationType {

    /** 승차. */
    BOARDING,
    /** 하차. */
    ALIGHTING,
    /** 미승차. */
    NO_SHOW,
    /** 결석. */
    ABSENT,
    /** 도착. */
    ARRIVE,
    /** 지연. */
    DELAY,
    /** 운행 시작. */
    RUN_STARTED,
    /** 운행 종료. */
    RUN_ENDED,
    /** 가입 승인 결과 결정. */
    SIGNUP_DECIDED,
    /** 변경 요청 결과 결정. */
    CHANGE_DECIDED,
    /** 승인 요청 발생. */
    APPROVAL_REQUESTED,
    /** 탑승 의사 변경됨. */
    INTENT_CHANGED,
    /** 연결 요청 발생. */
    LINK_REQUESTED,
    /** 노선 변경됨. */
    ROUTE_CHANGED,
    /** 배치 변경됨. */
    ASSIGNMENT_CHANGED,
    /** 미승차가 에스컬레이션됨. */
    NO_SHOW_ESCALATED,
    /** 비상 상황 발생. */
    EMERGENCY,
    /** 비상 상황 해제. */
    EMERGENCY_CANCELED;

    /** {@link NotificationType} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<NotificationType> {
        public Db() {
            super(NotificationType.class);
        }
    }
}
