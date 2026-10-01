package src.backend.audit.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 감사·접속 이력 조회 기간의 기본값(R46 I-05, API_SPEC §6.13) — {@code from} 을 안 주면 연도 1 부터 읽어 필터 없는 첫 화면의
 * 개수 쿼리가 보존 기간(2년) 전체를 훑었다(실측 순차 스캔 15,674 버퍼 · 137ms → 30일이면 962 버퍼 · 6.6ms).
 */
class AuditQueryRangeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void from_을_안_주면_지금으로부터_30일_전부터_읽는다() {
        AuditQueryRange range = AuditQueryRange.of(null, null, NOW);

        assertThat(range.from()).isEqualTo(NOW.minusDays(30));
        assertThat(range.to()).as("상한은 열어 둔다 — 미래 행은 없어 비용이 늘지 않는다").isAfter(NOW.plusYears(100));
    }

    @Test
    void to_만_주면_그_시각으로부터_30일_전부터_읽는다() {
        AuditQueryRange range = AuditQueryRange.of(null, "2026-06-30T00:00:00+09:00", NOW);

        assertThat(range.from()).as("지금이 아니라 to 기준이다 — 옛 기간을 보려고 to 만 줘도 빈 결과가 되지 않는다")
                .isEqualTo(OffsetDateTime.parse("2026-05-31T00:00:00+09:00"));
    }

    @Test
    void from_을_주면_그대로_쓴다() {
        AuditQueryRange range = AuditQueryRange.of("2024-01-01T00:00:00+09:00", "2024-12-31T23:59:59+09:00", NOW);

        assertThat(range.from()).isEqualTo(OffsetDateTime.parse("2024-01-01T00:00:00+09:00"));
        assertThat(range.to()).isEqualTo(OffsetDateTime.parse("2024-12-31T23:59:59+09:00"));
    }

    @Test
    void ISO_8601_이_아닌_값은_422_다() {
        assertThatThrownBy(() -> AuditQueryRange.of("어제", null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
