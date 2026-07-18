package src.backend.notification.entity;

/**
 * 알림 유형. dedup_key 구성 요소로도 쓰여 중복 발송을 억제한다.
 */
public enum NotificationType {
    BOARD_DONE,        // 승차 완료
    ALIGHT_DONE,       // 하차 완료
    APPROACH,          // 근접 (도착 5분 전)
    NO_SHOW,           // 미탑승 감지
    SOS,               // 긴급 SOS
    SCHEDULE_RESULT,   // 시간변경 요청 처리 결과
    CONNECTION_LOST    // 학생 위치 실시간 연결 끊김(유예시간 경과) — 기획서 원문엔 없는 확장 항목
}
