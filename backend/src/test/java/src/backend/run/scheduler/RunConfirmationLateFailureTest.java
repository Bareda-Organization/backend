package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 확정 실패 기록은 아직 {@code idle} 인 회차에만 남는다 — 다른 인스턴스가 먼저 확정한 회차에서 이쪽 시도가 실패하면, 확정된 회차에 {@code consecutive_failures = 1} 과
 * 재시도 시각이 붙어 관계자 화면의 확정 실패 경보가 거짓으로 켜진다.
 *
 * <p>회차를 집은 뒤 확정 시도 안에서 다른 쪽이 먼저 확정하는 경쟁을, 시도 도중에 상태를 {@code confirmed} 로 바꾸고 실패시키는 방식으로 결정적으로 만든다.
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunConfirmationLateFailureTest {

    @Autowired private RunConfirmationScheduler scheduler;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private RunConfirmationService confirmationService;

    private RunConfirmationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        RunConfirmationFixtures.cleanUpMarkedRows(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        RunConfirmationFixtures.cleanUpMarkedRows(jdbcTemplate);
    }

    @Test
    @DisplayName("집은 뒤 다른 쪽이 먼저 확정한 회차에서 이쪽 확정이 실패해도 실패 횟수와 재시도 시각은 그대로다")
    void 이미_확정된_회차의_실패는_기록하지_않는다() {
        long runId = 도래한_회차를_만든다();
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id != runId) {
                return null; // 다른 시험이 남긴 도래 회차는 확정하지 않는다
            }
            jdbcTemplate.update("UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE id = ?", id);
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }).when(confirmationService).confirmOne(anyLong());

        scheduler.confirmDueRuns();

        assertThat(jdbcTemplate.queryForMap("SELECT status, consecutive_failures, confirm_retry_at FROM run WHERE id = ?",
                runId))
                .containsEntry("status", "confirmed")
                .containsEntry("consecutive_failures", 0)
                .containsEntry("confirm_retry_at", null);
    }

    @Test
    @DisplayName("idle 로 남은 회차의 실패는 그대로 기록된다")
    void 아직_idle_인_회차의_실패는_기록한다() {
        long runId = 도래한_회차를_만든다();
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id != runId) {
                return null; // 다른 시험이 남긴 도래 회차는 확정하지 않는다
            }
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }).when(confirmationService).confirmOne(anyLong());

        scheduler.confirmDueRuns();

        assertThat(jdbcTemplate.queryForObject("SELECT consecutive_failures FROM run WHERE id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    private long 도래한_회차를_만든다() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        OffsetDateTime confirmAt = OffsetDateTime.parse("2030-04-01T11:59:00+09:00");
        return fixtures.idleRun(academyId, busId, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);
    }
}
