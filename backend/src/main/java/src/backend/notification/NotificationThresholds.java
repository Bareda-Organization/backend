package src.backend.notification;

import java.time.Duration;

/**
 * 알림 임계값(문서·코드 공통 상수) — PROJECT_MASTER_PLAN.md 7장 표와 동일하다.
 * NO_SHOW·APPROACH 는 {@code DriveSessionCommandService.checkApproachAndNoShow}가,
 * SOS_ESCALATION 은 sos 모듈이 이 상수를 참조해 스케줄러 판정에 사용한다.
 */
public final class NotificationThresholds {

    private NotificationThresholds() {
    }

    /** 정류소 도착 +10분 미승차 → NO_SHOW. */
    public static final Duration NO_SHOW = Duration.ofMinutes(10);

    /** 도착 예상 5분 전 → APPROACH. */
    public static final Duration APPROACH = Duration.ofMinutes(5);

    /** 관리자 미확인 3분 → SOS 플랫폼관리자 에스컬레이션. */
    public static final Duration SOS_ESCALATION = Duration.ofMinutes(3);
}
