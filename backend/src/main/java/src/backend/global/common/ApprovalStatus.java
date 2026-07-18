package src.backend.global.common;

/**
 * 승인 워크플로 공통 상태 — 결석 신고·시간변경 요청 등에서 재사용.
 */
public enum ApprovalStatus {
    PENDING,   // 대기
    APPROVED,  // 승인
    REJECTED   // 반려
}
