package src.backend.notification.entity;

import java.util.Set;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 알림 종류 21종 — {@code notification_log.type}(CHECK 로 강제, API_SPEC §9.7 과 값 일치 확인)의
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
    EMERGENCY_CANCELED,
    /** 예외 보고 접수됨(EXC-02·EXC-03, API_SPEC §4.13) — §9.7 표 누락분을 V5 마이그레이션으로 보강. */
    EXCEPTION_REPORTED,
    /** 승차 취소됨(BRD-05, 목표 13, Ruling 219) — 되돌리기로 승차 처리가 취소돼 원래 승차 알림이 거짓이 됐음을 정정. */
    BOARDING_CANCELED,
    /** 하차 취소됨(BRD-05, 목표 14, Ruling 219) — 되돌리기로 하차 처리가 취소돼 원래 하차 알림이 거짓이 됐음을 정정. */
    ALIGHTING_CANCELED;

    /**
     * NTF-10 이 수신 확인을 추적하는 <b>중요 통지</b> 3종 — {@code USER_FLOWS §10.2} 규칙4 가
     * "중요 알림(지연 · 미승차 · 노선 변경)" 으로 나열한 유일한 목록이고, 바로 다음 규칙5 가 그
     * "중요" 를 수신 확인 추적(NTF-10) 대상으로 잇는다.
     *
     * <p>🔴 <b>쓰는 쪽과 세는 쪽이 반드시 같은 집합을 봐야 한다.</b> {@code markRead} 는 이 3종에만
     * {@code acked} 를 남기는데 미확인 배지가 전 종류를 세면, 승하차·도착 알림이 영원히
     * {@code acked=false} 라 배지가 0 이 되지 않고 발송할 때마다 단조 증가한다(Ruling 227 — 병합
     * 전 실제로 그 상태였고 좌석 양쪽 시험 모두 통과했다). 그래서 상수를 여기 한 곳에 두고
     * 쓰는 쪽·세는 쪽이 함께 참조한다 — 넓히거나 좁히면 배지 대상도 같이 움직인다.
     *
     * <p>⚠ 정본이 이 분류를 {@code NotificationType} 값으로 못박은 문장은 부재하다 — 위 규칙4·5 를
     * 붙여 읽은 추론이다(Ruling 226·227). 정본이 명시하면 이 집합만 고치면 된다.
     */
    public static final Set<NotificationType> IMPORTANT_FOR_ACK =
            Set.of(DELAY, NO_SHOW, ROUTE_CHANGED);

    /** {@link NotificationType} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<NotificationType> {
        public Db() {
            super(NotificationType.class);
        }
    }
}
