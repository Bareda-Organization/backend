package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * 관리자가 ②구간 변경 요청을 승인·거절했음을 알리는 도메인 이벤트(API_SPEC §5.6) — {@code change_decided}
 * 알림(신청 학부모 앞)이 이 이벤트를 구독한다.
 *
 * <p>{@link ChangeRequestAutoRejectedEvent} 와 형태·구독자가 같은 알림({@code NotificationType
 * .CHANGE_DECIDED})을 만들지만, <b>관리자가 직접 처리한 결과</b>라는 점에서 발행측이 다르다
 * ({@code ChangeRequestDecisionService}) — 자동 거절과 결과 문구를 다르게 하려면 구독자가 두
 * 이벤트를 구분해야 하므로 타입을 합치지 않는다.
 *
 * @param approved     승인이면 {@code true}, 거절이면 {@code false} — {@code auto_rejected} 는 이
 *                      이벤트가 다루지 않는다({@link ChangeRequestAutoRejectedEvent} 전용)
 * @param rejectReason 거절 사유. 승인이면 {@code null} 이다({@link
 *                      src.backend.account.event.SignupDecidedEvent} 와 같은 형태)
 */
public record ChangeRequestDecidedEvent(Long academyId, Long changeRequestId, Long runId, Long studentId,
        Long requestedBy, boolean approved, String rejectReason, OffsetDateTime decidedAt) {
}
