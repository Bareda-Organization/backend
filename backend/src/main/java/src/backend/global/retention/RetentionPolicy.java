package src.backend.global.retention;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

/**
 * 보존 정리 배치가 무엇을 언제 지우는지 정하는 <b>유일한 지점</b>(목표 6·7, TECH_DECISIONS §12.2
 * "사양이 고정한 값은 코드 상수") — 보유 일수 · 배치 상한을 여기 상수로만 두고 yml 에는 두지 않는다.
 * {@code RetentionCleanupScheduler} 의 실행 주기(cron)만 yml 이 받는다.
 *
 * <p><b>여기 없는 테이블은 지우지 않는다.</b> {@code audit_log} · {@code rider_status_history} ·
 * {@code no_show_case} · {@code no_show_contact} · {@code exception_report} · {@code emergency_alert} ·
 * {@code route_version} · {@code run_stop} 은 무기한 보존 대상(ERD §7.1·§7.2)이라 이 클래스가 상수를
 * 두지 않았고, 상수가 없으면 그 테이블의 컷오프 자체를 계산할 수 없다 — 실수로 지우는 경로를 만들려면
 * 이 클래스에 상수를 먼저 더해야 하므로, 그 자체가 리뷰에서 눈에 띄는 변경이 된다.
 */
@Component
public class RetentionPolicy {

    /** 알림 로그 보관 기간 — 14일 확정(ERD §7.2 "notification_log — 14일 — 알림 보관 기간"). */
    public static final Duration NOTIFICATION_LOG_RETENTION = Duration.ofDays(14);

    /**
     * 위치 이력 보관 기간 — <b>잠정 · X-09</b>(ERD §8 "run_position 보유 기간 미설정 — 위치정보법
     * 검토 대기", Ruling 243 잠정 판정). 법정 요건이 확정되면 이 상수만 바꾸면 된다.
     */
    public static final Duration RUN_POSITION_RETENTION = Duration.ofDays(90);

    /**
     * 재발급 토큰이 만료되거나 폐기된 뒤 더 보관하는 기간 — <b>잠정 · X-09</b>(Ruling 243 잠정 판정).
     * 만료·폐기 즉시 지우지 않는 이유는 폐기 직후 그 토큰으로 재사용을 시도하는 정황을 30일 동안
     * 감사할 수 있게 남겨 두기 위함이다.
     */
    public static final Duration REFRESH_TOKEN_RETENTION_AFTER_EXPIRY_OR_REVOCATION = Duration.ofDays(30);

    /**
     * 한 번의 삭제 호출이 지우는 행 수 상한(목표 7) — 상한 없이 전건을 한 트랜잭션에서 지우면 오래
     * 쌓인 테이블에서 행 잠금을 길게 붙들어 운영 중 조회를 막는다. 상한을 넘는 나머지는 스케줄러가
     * 같은 틱 안에서 반복 호출해 여러 회차로 나눠 지운다.
     */
    public static final int BATCH_SIZE = 5_000;

    /** {@code notification_log} 삭제 기준 시각 — 이보다 이전에 생성된 행이 대상. */
    public OffsetDateTime notificationLogCutoff(OffsetDateTime now) {
        return now.minus(NOTIFICATION_LOG_RETENTION);
    }

    /** {@code run_position} 삭제 기준 시각 — 이보다 이전에 측정된 행이 대상. */
    public OffsetDateTime runPositionCutoff(OffsetDateTime now) {
        return now.minus(RUN_POSITION_RETENTION);
    }

    /**
     * {@code refresh_token} 삭제 기준 시각 — 만료일 · 폐기일 어느 쪽이든 이보다 이전이면 대상이다.
     * 아직 만료·폐기되지 않은 토큰은 이 기준과 무관하게 대상이 아니다(조회 조건이 별도로 가른다).
     */
    public OffsetDateTime refreshTokenCutoff(OffsetDateTime now) {
        return now.minus(REFRESH_TOKEN_RETENTION_AFTER_EXPIRY_OR_REVOCATION);
    }
}
