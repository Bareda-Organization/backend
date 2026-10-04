package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;

/** 지연 분 계산(Ruling 232 §3.1) 단독 시험 — §5.18·§5.3·§6.8·§6.18 이 같은 값을 쓴다는 근거다. */
class RunDelayCalculatorTest {

    private static final OffsetDateTime DEPART = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private static Run movingRun(OffsetDateTime startedAt) {
        Run run = Run.forSchedule(1L, 1L, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, DEPART,
                DEPART.minusMinutes(30), "출발지", "도착지", 40);
        run.start(startedAt);
        return run;
    }

    private static RunStop arrivedStop(int seq, OffsetDateTime eta, OffsetDateTime arrivedAt) {
        RunStop stop = RunStop.forStop(1L, 100L + seq, seq, eta);
        stop.markArrived(arrivedAt);
        return stop;
    }

    @Test
    @DisplayName("마지막으로 도착한 정차의 도착 시각 − 도착 예정이 지연 분이다")
    void 마지막_도착_정차의_늦은_만큼이_지연이다() {
        Run run = movingRun(DEPART);
        List<RunStop> stops = List.of(
                arrivedStop(1, DEPART.plusMinutes(5), DEPART.plusMinutes(5)),
                arrivedStop(2, DEPART.plusMinutes(10), DEPART.plusMinutes(17)),
                RunStop.forStop(1L, 103L, 3, DEPART.plusMinutes(15)));

        assertThat(RunDelayCalculator.minutesOf(run, stops)).isEqualTo(7);
    }

    @Test
    @DisplayName("일찍 도착했으면 음수가 아니라 0이다")
    void 일찍_도착은_0이다() {
        Run run = movingRun(DEPART);
        List<RunStop> stops = List.of(arrivedStop(1, DEPART.plusMinutes(10), DEPART.plusMinutes(4)));

        assertThat(RunDelayCalculator.minutesOf(run, stops)).isZero();
    }

    @Test
    @DisplayName("도착한 정차가 없으면 실제 출발 − 예정 출발이다")
    void 도착_전에는_출발_지연이다() {
        Run run = movingRun(DEPART.plusMinutes(6));

        assertThat(RunDelayCalculator.minutesOf(run, List.of())).isEqualTo(6);
    }

    @Test
    @DisplayName("예정보다 일찍 출발했거나 아직 출발하지 않았으면 0이다")
    void 일찍_출발이나_미출발은_0이다() {
        assertThat(RunDelayCalculator.minutesOf(movingRun(DEPART.minusMinutes(3)), List.of())).isZero();

        Run notStarted = Run.forSchedule(1L, 1L, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, DEPART,
                DEPART.minusMinutes(30), "출발지", "도착지", 40);
        assertThat(RunDelayCalculator.minutesOf(notStarted, List.of())).isZero();
    }
}
