package src.backend.student.repository;

/**
 * 승하차 알림(API_SPEC §4.6 {@code boarded}·{@code alighted}·{@code no_show})의 학부모 수신자
 * 조회 전용 투영 — {@link LinkedChild} 와 마찬가지로 알림 발송에 필요한 두 값만 꺼낸다.
 */
public interface GuardianAccountView {

    Long getAccountId();

    String getName();
}
