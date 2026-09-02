package src.backend.boarding.event;

import java.time.OffsetDateTime;

/**
 * 승차·하차가 반영됐음을 알리는 도메인 이벤트(BRD-01·02, API_SPEC §4.6) — 학부모에게만 알린다.
 *
 * <p>{@code no_show} 는 이 이벤트를 쓰지 않는다({@link RiderNoShowEvent} 참고) — 학부모·관계자 둘
 * 다에게 각각 다른 문구로 보내야 해서 수신 대상 수 자체가 다르다. {@code alighted} 뒤 관계자가 보는
 * "실시간 현황 갱신"(§4.6 표)은 WebSocket·대시보드 갱신이라 이 알림 로그 적재 대상이 아니다 — 이
 * Phase 의 목표(4·7·11·12·13·14) 어디에도 그 검사가 없다.
 *
 * <p>{@link src.backend.request.event.IntentChangedEvent} 와 같은 형태의 평범한 record 다.
 *
 * <p>{@code reverted}(목표 13·14, Ruling 219) — 되돌리기({@code BoardingCommandService#revert})도
 * 실제 상태가 바뀌었으므로 {@code rider_changed} WebSocket 방송({@link
 * src.backend.global.websocket.RiderChangedBroadcastListener})의 재료로 이 이벤트를 그대로 재사용한다
 * (그쪽은 이 필드를 읽지 않는다 — 방송 계약은 바뀌지 않는다). 다만 알림 로그 적재
 * ({@code BoardingNotificationListener#appendRiderStatusChanged})는 되돌리기를 <b>순방향 전이와
 * 같은 취급으로 처리하면 안 된다</b> — {@code status} 가 되돌아간 <i>결과</i> 값이라 "무엇이
 * 취소됐는지"를 말해주지 않고, 그걸 그대로 승차·하차 알림으로 적재하면 사실과 다른 문구가 나간다
 * (되돌리기 전용 정정 알림은 {@link RiderStatusRevertedEvent} 가 따로 나른다). 이 필드는 그
 * 리스너가 되돌리기 기원 이벤트를 걸러내는 유일한 수단이다.
 */
public record RiderStatusChangedEvent(Long runId, Long academyId, Long studentId, Long runRiderId,
        String status, OffsetDateTime changedAt, boolean reverted) {
}
