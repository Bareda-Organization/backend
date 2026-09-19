package src.backend.notification.domain.impl;

/**
 * 승하차 되돌리기 정정 알림 문구의 재료(BRD-05, API_SPEC §4.7) — {@link RiderStatusRevertedComposer}
 * 입력. {@link RiderStatusChangedSubject} 와 같은 이유로 도메인 이벤트를 그대로 넘기지 않는다.
 */
public record RiderStatusRevertedSubject(String canceledStatus, String studentName) {
}
