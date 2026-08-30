package src.backend.request.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.domain.ChangeWindow;

/**
 * {@link BoardingIntent} 의 한도 판정·소비·복구, {@code riding} 적용 메서드 단위 테스트 — DB·Spring
 * 컨텍스트 없이 순수 도메인 규칙만 검증한다.
 */
class BoardingIntentTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T07:35:00+09:00");

    @Test
    void 생성_직후는_한도가_남아있다() {
        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, NOW);

        assertThat(intent.hasChangeQuota()).isTrue();
    }

    @Test
    void 한도_소비_1회는_성공하고_카운터가_1이_된다() {
        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, NOW);

        intent.consumeChangeQuota();

        assertThat(intent.getChangeUsedCount()).isEqualTo(1);
        assertThat(intent.hasChangeQuota()).isFalse();
    }

    @Test
    void 한도_소비_2회째는_403_CHANGE_LIMIT_REACHED_로_거부된다() {
        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, NOW);
        intent.consumeChangeQuota();

        assertThatThrownBy(intent::consumeChangeQuota)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CHANGE_LIMIT_REACHED));

        assertThat(ErrorCode.CHANGE_LIMIT_REACHED.getStatus().value())
                .as("경쟁 없는 정책 위반은 403 이다")
                .isEqualTo(403);
    }

    @Test
    void 복구_후_다시_1회_소비할_수_있다() {
        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, NOW);
        intent.consumeChangeQuota();

        intent.restoreChangeQuota();

        assertThat(intent.getChangeUsedCount()).isEqualTo(0);
        assertThat(intent.hasChangeQuota()).isTrue();
        intent.consumeChangeQuota();
        assertThat(intent.getChangeUsedCount()).isEqualTo(1);
    }

    @Test
    void riding_적용이_구간_변경시각_처리자를_함께_기록한다() {
        BoardingIntent intent = BoardingIntent.forRun(1L, 1L, NOW);
        OffsetDateTime changedAt = NOW.plusMinutes(1);

        intent.applyRiding(false, ChangeWindow.APPROVAL_REQUIRED, changedAt, 99L);

        assertThat(intent.isRiding()).isFalse();
        assertThat(intent.getAppliedSegment()).isEqualTo(ChangeWindow.APPROVAL_REQUIRED.code());
        assertThat(intent.getChangedAt()).isEqualTo(changedAt);
        assertThat(intent.getChangedBy()).isEqualTo(99L);
    }
}
