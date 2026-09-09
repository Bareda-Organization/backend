package src.backend.exception.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import src.backend.exception.entity.EmergencyType;

/**
 * 비상 신고가 접수됐음을 알리는 도메인 이벤트(EXC-04, Phase 11 T2 목표 6·8·11) — 학원 관계자·
 * 메인관리자 알림(설정과 무관하게 항상 발송, 목표 6)과 WebSocket 방송(관리자 콘솔 실시간 반영,
 * 목표 11) 둘 다 이 이벤트 하나를 구독한다({@link src.backend.run.event.RunStartedEvent} 와 같은
 * "한 이벤트, 리스너 둘" 형태).
 *
 * <p>{@code memo} 는 여전히 담지 않는다 — 알림 문구 컴포저가 경로 변경 알림에 명시한 이유와 같다:
 * 알림 문구는 기기 알림함·잠금화면에 그대로 노출되므로 상세를 싣지 않고, 상세는 REST 조회
 * ({@code GET /staff/emergencies}·{@code GET /admin/emergencies})로만 연다.
 *
 * <p>{@code raisedBy}·{@code position}·{@code riderCount} 는 Phase 14 이월 ②(목표 10)에서 추가됐다
 * — WebSocket 방송({@code emergency_raised}, {@code API_SPEC §7.1})이 7필드를 요구하면서 생긴 채널
 * 차이다: 알림함 문구는 여전히 {@code busNo}·{@code type} 만으로 최소 골자를 유지하지만, WS 는
 * 관계자·메인 관리자 채널 전용(C-17)이라 REST 조회와 동등한 상세를 실시간으로 실어야 한다. 발행측
 * 이벤트는 구독측(알림 모듈)의 타입을 참조하지 않는다(§7 규칙 17)는 경계는 그대로 지킨다 — 알림
 * 리스너는 이 필드들을 읽지 않는다.
 */
public record EmergencyRaisedEvent(Long emergencyId, Long academyId, Long runId, String busNo, EmergencyType type,
        RaisedBy raisedBy, Position position, Integer riderCount, OffsetDateTime raisedAt) {

    /** 발신자 표시(API_SPEC §7.1) — 관계자·메인 관리자 채널 전용(C-17)이라 연락처까지 그대로 싣는다. */
    public record RaisedBy(String name, String role, String phone) {
    }

    /** 발신 시점 스냅샷 좌표(API_SPEC §7.1·§3.2, {@code emergency_alert.lat/lng}) — 캐시 부재면 둘 다 null. */
    public record Position(BigDecimal lat, BigDecimal lng) {
    }
}
