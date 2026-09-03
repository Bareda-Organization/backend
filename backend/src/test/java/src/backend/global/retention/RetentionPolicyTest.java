package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * {@link RetentionPolicy} 컷오프 계산 단위 시험(목표 6) — DB 없이 순수 계산만 검증한다. 각 테이블의
 * 실제 삭제 여부는 {@link RetentionCleanupSchedulerTest} 가 담당한다.
 */
class RetentionPolicyTest {

    private final RetentionPolicy policy = new RetentionPolicy();

    private final OffsetDateTime now = OffsetDateTime.of(2032, 6, 15, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void 알림_로그_컷오프는_14일_전이다() {
        assertThat(RetentionPolicy.NOTIFICATION_LOG_RETENTION).isEqualTo(Duration.ofDays(14));
        assertThat(policy.notificationLogCutoff(now)).isEqualTo(now.minusDays(14));
    }

    @Test
    void 위치_이력_컷오프는_90일_전이다() {
        assertThat(RetentionPolicy.RUN_POSITION_RETENTION).isEqualTo(Duration.ofDays(90));
        assertThat(policy.runPositionCutoff(now)).isEqualTo(now.minusDays(90));
    }

    @Test
    void 재발급_토큰_컷오프는_30일_전이다() {
        assertThat(RetentionPolicy.REFRESH_TOKEN_RETENTION_AFTER_EXPIRY_OR_REVOCATION).isEqualTo(Duration.ofDays(30));
        assertThat(policy.refreshTokenCutoff(now)).isEqualTo(now.minusDays(30));
    }

    @Test
    void 배치_상한은_5000이다() {
        assertThat(RetentionPolicy.BATCH_SIZE).isEqualTo(5_000);
    }
}
