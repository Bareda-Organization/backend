package src.backend.rideevent.entity;

/**
 * 승하차 기록의 출처. 정정(CORRECTION)은 원본을 덮어쓰지 않고 새 기록으로 남긴다.
 */
public enum RideSource {
    QR,          // QR 스캔
    NFC,         // NFC 태그
    MANUAL,      // 기사 수동 확인
    CORRECTION   // 사후 정정
}
