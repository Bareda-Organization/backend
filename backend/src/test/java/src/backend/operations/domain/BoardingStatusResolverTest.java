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
                false, List.of(RideType.BOARD, RideType.ALIGHT), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ALIGHTED);
    }

    @Test
    void handover_is_also_alighted() {
        assertThat(BoardingStatusResolver.resolve(
                false, List.of(RideType.BOARD, RideType.HANDOVER), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ALIGHTED);
    }

    @Test
    void board_only_is_boarded() {
        assertThat(BoardingStatusResolver.resolve(
                false, List.of(RideType.BOARD), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.BOARDED);
    }

    /**
     * 서버가 승하차 순서를 검증하지 않아(BG-1, {@code RideEventCommandService}가 버스·학생·학원만
     * 확인하고 직전 상태를 조회하지 않음) BOARD 기록 없이 ALIGHT 만 들어오는 입력이 실제로 가능하다.
     * 지금은 ALIGHTED 를 반환하는데, 나중에 우선순위 조건이 "BOARD 도 있어야 ALIGHTED"로 좁혀지면
     * 이 입력이 조용히 NO_SHOW 로 뒤바뀔 수 있어 이 조합을 별도로 고정한다.
     */
    @Test
    void alight_withoutBoard_isStillAlighted() {
        assertThat(BoardingStatusResolver.resolve(
                false, List.of(RideType.ALIGHT), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ALIGHTED);
    }

    /** 세션이 아직 시작 안 됐으면 도달 시각을 계산할 근거가 없다 — 전원 대기다. */
    @Test
    void noRecord_beforeSessionStart_isWaiting() {
        assertThat(BoardingStatusResolver.resolve(false, List.of(), null, NOW, MISSED))
                .isEqualTo(BoardingStatus.WAITING);
    }

    @Test
    void noRecord_stopNotReached_isWaiting() {
        assertThat(BoardingStatusResolver.resolve(false, List.of(), NOW.plusMinutes(5), NOW, MISSED))
                .isEqualTo(BoardingStatus.WAITING);
    }

    @Test
    void noRecord_justReached_isWaiting() {
        assertThat(BoardingStatusResolver.resolve(false, List.of(), NOW.minusMinutes(3), NOW, MISSED))
                .isEqualTo(BoardingStatus.WAITING);
    }

    /** 이 경계가 화면의 "누락" 표시를 가른다 — 알림(미승차 10분)과 같은 기준이어야 한다. */
    @Test
    void noRecord_pastThreshold_isNoShow() {
        assertThat(BoardingStatusResolver.resolve(false, List.of(), NOW.minusMinutes(10), NOW, MISSED))
                .isEqualTo(BoardingStatus.NO_SHOW);
    }

    @Test
    void noRecord_pastThresholdByOneSecond_isNoShow() {
        assertThat(BoardingStatusResolver.resolve(
                false, List.of(), NOW.minusMinutes(10).minusSeconds(1), NOW, MISSED))
                .isEqualTo(BoardingStatus.NO_SHOW);
    }

    /** 임계 직전(경과 = threshold - 1초)은 아직 WAITING — 부등호가 {@code >=}에서 {@code >}로 뒤집혀도
     *  이 테스트와 {@link #noRecord_pastThreshold_isNoShow}(정각) 양쪽에서 실패가 걸린다. */
    @Test
    void noRecord_justBeforeThreshold_isWaiting() {
        assertThat(BoardingStatusResolver.resolve(
                false, List.of(), NOW.minusMinutes(10).plusSeconds(1), NOW, MISSED))
                .isEqualTo(BoardingStatus.WAITING);
    }

    /**
     * 결석 신고가 승인됐으면 승하차 기록이 둘 다 있어도(BOARD·ALIGHT) ABSENT 가 이긴다 — 우선순위 1위.
     * 사전에 결석 처리된 학생의 기록이 남아 있는 상황(오기록·중복 세션 등)에서도 화면은 여전히
     * "미등원"을 보여줘야 한다.
     */
    @Test
    void absent_beatsAlightedEvenWithFullRecords() {
        assertThat(BoardingStatusResolver.resolve(
                true, List.of(RideType.BOARD, RideType.ALIGHT), NOW.minusMinutes(30), NOW, MISSED))
                .isEqualTo(BoardingStatus.ABSENT);
    }

    /**
     * 결석 신고 승인 + 기록 부재 + 임계 초과 상황에서도 NO_SHOW 가 아니라 ABSENT 다. 사전에 안 탄다고
     * 한 학생을 현장 미승차로 표시하면 관계자가 헛되이 학부모에게 연락하게 된다.
     */
    @Test
    void absent_beatsNoShow() {
        assertThat(BoardingStatusResolver.resolve(
                true, List.of(), NOW.minusMinutes(10), NOW, MISSED))
                .isEqualTo(BoardingStatus.ABSENT);
    }

    /** 결석 신고 승인 + 세션 미시작(도달 시각 부재)도 ABSENT — WAITING 으로 새지 않는다. */
    @Test
    void absent_beatsWaiting() {
        assertThat(BoardingStatusResolver.resolve(true, List.of(), null, NOW, MISSED))
                .isEqualTo(BoardingStatus.ABSENT);
    }

    @Test
    void isStopReached_beforeReachedTime_isFalse() {
        assertThat(BoardingStatusResolver.isStopReached(NOW.plusMinutes(5), NOW)).isFalse();
    }

    @Test
    void isStopReached_atReachedTime_isTrue() {
        assertThat(BoardingStatusResolver.isStopReached(NOW, NOW)).isTrue();
    }

    @Test
    void isStopReached_sessionNotStarted_isFalse() {
        assertThat(BoardingStatusResolver.isStopReached(null, NOW)).isFalse();
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
