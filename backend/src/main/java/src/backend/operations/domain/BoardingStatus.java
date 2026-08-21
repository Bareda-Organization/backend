package src.backend.operations.domain;

/**
 * 관리자 화면이 학생 1명의 승하차 진행 상태를 표시할 때 쓰는 5단계 판정.
 * {@link BoardingStatusResolver#resolve}가 이 값을 계산한다.
 */
public enum BoardingStatus {

    /** 하차(ALIGHT) 또는 보호자 인계완료(HANDOVER) 기록 존재. */
    ALIGHTED,

    /** 승차(BOARD) 기록은 존재하나 하차 기록은 부재. */
    BOARDED,

    /** 기록 부재 + 정차 미도달, 또는 세션 자체가 아직 시작되지 않음. */
    UPCOMING,

    /** 기록 부재 + 정차 도달, 경과 시간이 임계값 미만. */
    PENDING,

    /** 기록 부재 + 정차 도달, 경과 시간이 임계값 이상 — 미승차로 간주. */
    MISSED
}
