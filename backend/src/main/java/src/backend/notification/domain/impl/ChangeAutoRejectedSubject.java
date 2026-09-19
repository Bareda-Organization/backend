package src.backend.notification.domain.impl;

/**
 * 변경 요청 자동 거절 알림 문구의 재료(API_SPEC §1.6·§9.7 {@code change_decided}) —
 * {@link ChangeAutoRejectedComposer} 입력.
 *
 * <p>{@link src.backend.request.event.ChangeRequestAutoRejectedEvent} 를 그대로 넘기지 않는다 —
 * 그 이벤트는 {@code studentId} 만 나르고 문구 전용 필드(자녀 이름, ATT-03)는 없다({@link
 * DelaySubject} 와 같은 근거).
 */
public record ChangeAutoRejectedSubject(String studentName) {
}
