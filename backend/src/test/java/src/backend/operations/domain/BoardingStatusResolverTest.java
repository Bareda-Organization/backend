package src.backend.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.rideevent.entity.RideType;

/**
 * 탑승 상태 판정 단위 테스트. 시각을 인자로 받는 순수 함수라 대기 없이 검증한다.
 * 임계값은 미승차 알림과 같은 기준(10분)을 쓴다 — 화면과 알림이 다른 기준으로 "누락"을 말하면 안 된다.
 */
class BoardingStatusResolverTest {

    private static final Duration MISSED = Duration.ofMinutes(10);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 8, 30);

    @Test
    void alight_beats_board() {
        assertThat(BoardingStatusResolver.resolve(
                List.of(RideType.BOARD, RideType.ALIGHT), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ALIGHTED);
    }

    @Test
    void handover_is_also_alighted() {
        assertThat(BoardingStatusResolver.resolve(
                List.of(RideType.BOARD, RideType.HANDOVER), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ALIGHTED);
    }

    @Test
    void board_only_is_boarded() {
        assertThat(BoardingStatusResolver.resolve(
                List.of(RideType.BOARD), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.BOARDED);
    }

    /** 세션이 아직 시작 안 됐으면 도달 시각을 계산할 근거가 없다 — 전원 예정이다. */
    @Test
    void noRecord_beforeSessionStart_isUpcoming() {
        assertThat(BoardingStatusResolver.resolve(List.of(), null, NOW, MISSED))
                .isEqualTo(BoardingStatus.UPCOMING);
    }

    @Test
    void noRecord_stopNotReached_isUpcoming() {
        assertThat(BoardingStatusResolver.resolve(List.of(), NOW.plusMinutes(5), NOW, MISSED))
                .isEqualTo(BoardingStatus.UPCOMING);
    }

    @Test
    void noRecord_justReached_isPending() {
        assertThat(BoardingStatusResolver.resolve(List.of(), NOW.minusMinutes(3), NOW, MISSED))
                .isEqualTo(BoardingStatus.PENDING);
    }

    /** 이 경계가 화면의 "누락" 표시를 가른다 — 알림(미승차 10분)과 같은 기준이어야 한다. */
    @Test
    void noRecord_pastThreshold_isMissed() {
        assertThat(BoardingStatusResolver.resolve(List.of(), NOW.minusMinutes(10), NOW, MISSED))
                .isEqualTo(BoardingStatus.MISSED);
    }

    @Test
    void noRecord_exactlyAtThresholdBoundary_isMissed() {
        assertThat(BoardingStatusResolver.resolve(List.of(), NOW.minusMinutes(10).minusSeconds(1), NOW, MISSED))
                .isEqualTo(BoardingStatus.MISSED);
    }

    @Test
    void estimateStopReachedAt_addsEtaToSessionStart() {
        assertThat(BoardingStatusResolver.estimateStopReachedAt(NOW, 300L))
                .isEqualTo(NOW.plusMinutes(5));
    }

    /** 세션 미시작이면 도달 시각을 추정할 수 없다. */
    @Test
    void estimateStopReachedAt_withoutSessionStart_isNull() {
        assertThat(BoardingStatusResolver.estimateStopReachedAt(null, 300L)).isNull();
    }
}
