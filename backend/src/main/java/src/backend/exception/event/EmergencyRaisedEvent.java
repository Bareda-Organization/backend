package src.backend.exception.event;

import java.time.OffsetDateTime;

import src.backend.exception.entity.EmergencyType;

/**
 * 비상 신고가 접수됐음을 알리는 도메인 이벤트(EXC-04, Phase 11 T2 목표 6·8·11) — 학원 관계자·
 * 메인관리자 알림(설정과 무관하게 항상 발송, 목표 6)과 WebSocket 방송(관리자 콘솔 실시간 반영,
 * 목표 11) 둘 다 이 이벤트 하나를 구독한다({@link src.backend.run.event.RunStartedEvent} 와 같은
 * "한 이벤트, 리스너 둘" 형태).
 *
 * <p>{@code memo}·{@code lat}·{@code lng}·{@code riderCount} 를 담지 않는다 —
 * {@link src.backend.notification.domain.impl.RouteChangedComposer} 가 명시한 이유와 같다: 알림
 * 문구는 기기 알림함·잠금화면에 그대로 노출되므로 상세를 싣지 않고, 상세는 REST 조회
 * ({@code GET /staff/emergencies}·{@code GET /admin/emergencies})로만 연다. {@code busNo}·
 * {@code type} 은 "어느 차량의 어떤 상황인가"라는 문구의 최소 골자라 예외로 싣는다.
 */
public record EmergencyRaisedEvent(Long emergencyId, Long academyId, Long runId, String busNo,
        EmergencyType type, OffsetDateTime raisedAt) {
}
