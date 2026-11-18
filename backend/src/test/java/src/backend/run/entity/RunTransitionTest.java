package src.backend.run.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.enums.Direction;

/**
 * 회차 상태 기계(FEATURE_SPEC §6)가 허용하지 않는 전이는 엔티티가 거절한다 — {@code idle → confirmed} 는 확정 배치의 조건부 UPDATE 가 하고,
 * 엔티티가 하는 전이는 {@code confirmed → moving}(운행 시작) 과 {@code moving → finished}(종료) 둘뿐이다. 그 밖의 상태에서 부르면 상태와
 * 시각을 바꾸지 않고 {@link IllegalStateException} 이다(서비스가 앞서 잠그고 판정하므로 여기에 닿으면 호출부의 결함이다).
 */
class RunTransitionTest {

    private static final OffsetDateTime DEPART = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private static final OffsetDateTime NOW = DEPART.minusMinutes(2);

    private static Run runIn(RunStatus status) {
        Run run = Run.forSchedule(new RunPlan(1L, 1L, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, DEPART,
                DEPART.minusMinutes(30), "출발지", "도착지", 40));
        ReflectionTestUtils.setField(run, "status", status);
        return run;
    }

    @Test
    @DisplayName("confirmed 회차는 시작하면 moving 이고 시작 시각이 기록된다")
    void 확정된_회차는_시작할_수_있다() {
        Run run = runIn(RunStatus.CONFIRMED);

        run.start(NOW);

        assertThat(run.getStatus()).isEqualTo(RunStatus.MOVING);
        assertThat(run.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("moving 회차는 종료하면 finished 이고 종료 시각이 기록되며 보류 표시가 걷힌다")
    void 이동_중인_회차는_종료할_수_있다() {
        Run run = runIn(RunStatus.MOVING);
        run.deferFinish();

        run.finish(NOW);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FINISHED);
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        assertThat(run.isFinishPending()).isFalse();
    }

    @ParameterizedTest(name = "{0} 에서 시작은 거절")
    @EnumSource(value = RunStatus.class, names = {"IDLE", "MOVING", "FINISHED"})
    @DisplayName("confirmed 가 아닌 회차의 시작은 상태를 바꾸지 않고 거절된다")
    void 확정되지_않은_회차의_시작은_거절된다(RunStatus status) {
        Run run = runIn(status);

        assertThatThrownBy(() -> run.start(NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(run.getStatus()).isEqualTo(status);
        assertThat(run.getStartedAt()).isNull();
    }

    @ParameterizedTest(name = "{0} 에서 종료는 거절")
    @EnumSource(value = RunStatus.class, names = {"IDLE", "CONFIRMED", "FINISHED"})
    @DisplayName("moving 이 아닌 회차의 종료는 상태를 바꾸지 않고 거절된다")
    void 이동_중이_아닌_회차의_종료는_거절된다(RunStatus status) {
        Run run = runIn(status);

        assertThatThrownBy(() -> run.finish(NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(run.getStatus()).isEqualTo(status);
        assertThat(run.getFinishedAt()).isNull();
    }
}
