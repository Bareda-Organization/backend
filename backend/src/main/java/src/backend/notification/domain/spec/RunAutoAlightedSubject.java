package src.backend.notification.domain.spec;

/**
 * 등원 최종 도착 자동 하차 알림 문구의 재료(API_SPEC §9.7 {@code alighting}) — {@link
 * RunAutoAlightedComposer} 입력.
 */
public record RunAutoAlightedSubject(String studentName) {
}
