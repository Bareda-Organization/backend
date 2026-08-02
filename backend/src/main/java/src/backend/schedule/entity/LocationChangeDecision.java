package src.backend.schedule.entity;

/**
 * 학부모 등하원 위치 변경 요청의 자동 판정 결과(BE-10, OVERVIEW §4.4).
 * 관리자 승인 단계가 없으므로 접수 시점에 즉시 확정되며, 이후 상태전이는 일어나지 않는다.
 */
public enum LocationChangeDecision {
    /** 배차·계획이 없어 좌표만 갱신. 이후 관리자가 배차한다 */
    APPLIED,
    /** 계획이 있었고 임계 이내라 재계산해 새 version 을 배포했다 */
    REPLANNED,
    /** 임계 초과 — 좌표를 바꾸지 않았다 */
    REJECTED,
    /** 당일 운행 세션이 이미 존재 — 접수하지 않았다 */
    BLOCKED
}
