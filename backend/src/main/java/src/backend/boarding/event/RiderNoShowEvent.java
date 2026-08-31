package src.backend.boarding.event;

import java.time.OffsetDateTime;

/**
 * 미승차가 확정됐음을 알리는 도메인 이벤트(BRD-04, API_SPEC §4.6 {@code status=no_show}) — 학부모
 * "즉시 발송"과 관계자 "미승차 카운트 +1" 둘 다 이 이벤트 하나를 구독한다(§4.6 표).
 *
 * <p>{@link RiderStatusChangedEvent} 와 합치지 않고 따로 둔다 — 저쪽은 수신자가 학부모 1갈래인데
 * 이쪽은 학부모·관계자 2갈래라, 하나의 이벤트에 선택적 필드로 얹으면 리스너가 "이번엔 몇 명에게
 * 보내야 하는가"를 이벤트 밖에서(상태값 분기로) 다시 판단해야 한다. {@code caseId} 는 케이스
 * 생성(목표 7)이 만든 {@code no_show_case} 의 식별자다 — 에스컬레이션 3분 카운트다운은 Phase 11.
 */
public record RiderNoShowEvent(Long runId, Long academyId, Long studentId, Long runRiderId, Long caseId,
        OffsetDateTime changedAt) {
}
