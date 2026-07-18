package src.backend.sos.entity;

/**
 * SOS 처리 상태. 되돌릴 수 없는 단방향 전이: OPEN → ACKNOWLEDGED → RESOLVED.
 */
public enum SosStatus {
    OPEN,          // 발신 (미확인)
    ACKNOWLEDGED,  // 관리자 확인
    RESOLVED       // 종료
}
