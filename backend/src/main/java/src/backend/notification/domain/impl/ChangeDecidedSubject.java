package src.backend.notification.domain.impl;

/**
 * ②구간 변경 요청 관리자 결정 알림 문구의 재료(API_SPEC §5.6·§9.7 {@code change_decided}) —
 * {@link ChangeDecidedComposer} 입력.
 *
 * <p>{@link src.backend.request.event.ChangeRequestDecidedEvent} 를 그대로 넘기지 않는다 —
 * 그 이벤트는 {@code studentId} 만 나르고 문구 전용 필드(자녀 이름, ATT-03)는 없다({@link
 * DelaySubject} 와 같은 근거).
 */
public record ChangeDecidedSubject(boolean approved, String rejectReason, String studentName) {
}
