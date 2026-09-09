package src.backend.exception.event;

import java.time.OffsetDateTime;

/**
 * 1분 이내 취소된 비상 신고를 알리는 도메인 이벤트(EXC-04, Phase 11 T2 목표 9) —
 * {@link EmergencyRaisedEvent} 와 같은 수신자(학원 관계자·메인관리자)에게 취소 사실을 통지한다.
 */
public record EmergencyCanceledEvent(Long emergencyId, Long academyId, Long runId, String busNo,
        OffsetDateTime canceledAt) {
}
