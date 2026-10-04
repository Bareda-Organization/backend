package src.backend.global.policy;

import java.time.Duration;

/**
 * 전역 정책 상수의 <b>유일한 정의 지점</b>(FEATURE_SPEC §2.1, IMPLEMENTATION_PLAN §7 규칙 10) — 학원이 바꿀 수 없는 값을 여기 두고,
 * 판정 코드와 {@code GET /staff/academy-settings} 의 {@code policy}(API_SPEC §5.21)가 <b>같은 값을 읽는다</b>.
 * 화면에 숫자를 박으면 상수가 바뀔 때 갈리기 때문이다(Ruling 820).
 *
 * <p>설정(yml)으로 빼지 않는다 — 운영에서 사양 값이 조용히 바뀐다. 알림 보관 14일은 보존 정리 배치와 같은 값이라
 * {@code RetentionPolicy#NOTIFICATION_LOG_RETENTION} 이 이미 정의처다(여기 다시 두지 않는다).
 */
public final class PolicyConstants {

    /**
     * 노선 확정 시점 — 출발 이 시간 전이다(C-03). DB 의 {@code ck_run_confirm_at} CHECK(출발 − 30분)와 <b>같은 값이어야 한다</b> —
     * 어긋나면 저장이 제약 위반으로 거부된다.
     */
    public static final Duration CONFIRM_LEAD = Duration.ofMinutes(30);

    /** 운행 시작 버튼이 열리는 창 — 출발 시각 앞뒤 이 시간(양끝 포함, RUN-02 · Ruling 202). */
    public static final Duration START_WINDOW = Duration.ofMinutes(10);

    /** ②구간 변경 한도 — 회차 하나에 이 횟수까지다(C-04). {@code boarding_intent.change_used_count} CHECK 와 같은 값이다. */
    public static final int CHANGE_QUOTA_PER_RUN = 1;

    /** 지연 알림 단위(분) — 지연 알림의 예상 지연 시간은 이 값의 배수여야 한다(NTF-06). */
    public static final int DELAY_UNIT_MINUTES = 5;

    /** 근접 알림 기준(m) — 버스가 다음 승하차지에 이 거리 이내로 들어오면 알린다(NTF-04 · Ruling 207). */
    public static final int PROXIMITY_ALERT_METERS = 300;

    private PolicyConstants() {
    }
}
