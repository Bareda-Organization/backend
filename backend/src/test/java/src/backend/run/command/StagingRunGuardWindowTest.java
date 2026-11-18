package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunPlan;
import src.backend.run.repository.RunRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 강제 추가 · 이동 저장은 회차 행을 잠근 뒤 <b>그 시각의 구간</b>으로 다시 판정한다 — 구간 판정과 저장 사이에 지오코딩이 수 초 끼므로, 판정을 잠금 앞에서만 하면 확정 시각이 지난 뒤에
 * 잠금을 얻은 요청이 ①구간으로 저장된다.
 *
 * <p>요청이 들어온 시각에는 아직 ①구간이던 회차를 읽어 둔 채(낡은 {@code Run}) 시계가 확정 시각을 지난 뒤 잠금에 닿는 경우를, 확정 시각이 이미 지난 회차를 잠그는 것으로 재현한다.
 */
@SpringBootTest
@Transactional
@Import(FixedClock20300401Config.class)
class StagingRunGuardWindowTest {

    /** 고정 시계({@code FixedClock20300401Config})가 가리키는 정오(KST). */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");

    @Autowired private ApplicationContext applicationContext;
    @Autowired private RunRepository runRepository;
    @Autowired private StagingRunGuard stagingRunGuard;

    @Test
    @DisplayName("확정 시각이 지났으면 아직 idle 이어도 잠근 회차는 CHANGE_WINDOW_CLOSED 다")
    void 확정_시각이_지난_회차는_잠금_뒤에_거절된다() {
        Run run = 회차(NOW.minusSeconds(1));

        assertThatThrownBy(() -> stagingRunGuard.lockIdle(run)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHANGE_WINDOW_CLOSED));
    }

    @Test
    @DisplayName("확정 시각이 정확히 지금이면 ②구간이라 거절된다")
    void 확정_시각이_지금이면_거절된다() {
        Run run = 회차(NOW);

        assertThatThrownBy(() -> stagingRunGuard.lockIdle(run)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHANGE_WINDOW_CLOSED));
    }

    @Test
    @DisplayName("확정 시각 전의 idle 회차는 잠금을 통과한다")
    void 확정_시각_전의_회차는_통과한다() {
        Run run = 회차(NOW.plusSeconds(1));

        assertThatCode(() -> stagingRunGuard.lockIdle(run)).doesNotThrowAnyException();
    }

    private Run 회차(OffsetDateTime confirmAt) {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        Run created = Run.forSchedule(new RunPlan(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt, "출발지", "도착지", null));
        return runRepository.saveAndFlush(created);
    }
}
