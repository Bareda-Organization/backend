package src.backend.rideevent.entity;

/**
 * 승하차 구분. HANDOVER(보호자 인계완료)는 ALIGHT(하차완료)와 구분되는 다음 단계 — 하차만으로는
 * "학생이 버스에서 내렸다"만 보장하고, 보호자가 실제로 인계받았는지는 별도 기록이 필요하다(§5).
 * 현재는 학년 등 대상 제한 없이 전 학생 대상 선택적 기록이며, 강제(미기록 시 차단) 정책은 아직 없다.
 */
public enum RideType {
    BOARD,      // 승차
    ALIGHT,     // 하차
    HANDOVER    // 보호자 인계완료
}
