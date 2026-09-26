package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import java.time.Clock;
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
import src.backend.global.common.enums.Weekday;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 확정 배치가 명단을 읽은 뒤 노선 계산(수 초)을 하는 사이에 들어온 강제 추가·이동이 사라지지 않는지 본다(BR-044).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 계산 도중의 삽입이 별도 커밋이어야 실제 경합과 같고, 실패한 확정이
 * 롤백돼 다음 시도가 다시 읽는지를 봐야 한다. 뒷정리는 {@link RunConfirmationFixtures#ACADEMY_NAME} 표시 행을
 * 직접 지운다({@code RunConfirmationSchedulerTest} 와 같은 형태).
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunConfirmationStagingRaceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    @MockitoSpyBean
    private RouteComputationPipeline pipeline;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private RunConfirmationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpMarkedRows();
    }

    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("BR-044 — 계산 도중 커밋된 강제 추가는 이번 확정을 무르고 다음 확정에 합류한다")
    void 계산_중에_들어온_강제_추가는_다음_확정에_반영된다() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures.student(academyId, "학생1");
        fixtures.verifiedAddress(student, firstStop, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long lateStudent = fixtures.student(academyId, "늦은학생");
        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(1);
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, confirmAt.plusMinutes(30),
                confirmAt);

        doAnswer(invocation -> {
            Object computation = invocation.callRealMethod();
            jdbcTemplate.update("INSERT INTO run_forced_addition (run_id, student_id, stop_id, added_by) "
                    + "VALUES (?, ?, ?, 1)", runId, lateStudent, lastStop);
            return computation;
        }).when(pipeline).compute(any());
        try {
            confirmationService.confirmOne(runId);
        } catch (RuntimeException raceDetected) {
            // 경합을 알아채면 이 회차만 실패하고 idle 로 남는다 — 배치는 다음 틱에 다시 읽는다.
        }
        doCallRealMethod().when(pipeline).compute(any());
        confirmationService.confirmOne(runId);

        assertThat(jdbcTemplate.queryForList("SELECT student_id FROM run_rider WHERE run_id = ?", Long.class, runId))
                .as("201 을 받은 강제 추가가 이미 지나간 확정에 묻혀 영구 대기로 남으면 안 된다")
                .contains(student, lateStudent);
    }
}
