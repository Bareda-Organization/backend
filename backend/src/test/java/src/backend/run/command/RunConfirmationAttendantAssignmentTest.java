package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.entity.WorkHours;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 배치의 동승자 자동 배정(Ruling 330, BR-018) — 동승자 자리가 빈 회차에만 배정하고, 이미 있으면 건드리지
 * 않으며, 배정되면 {@link AssignmentChangedEvent} 를 낸다.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class RunConfirmationAttendantAssignmentTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final WorkHours ALL_DAY_MONDAY = WorkHours.of(
            Map.of("mon", List.of(Map.of("start", "00:00", "end", "23:59"))));

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

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
    private ApplicationEvents applicationEvents;

    @Autowired
    private Clock clock;

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2030-04-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    private long confirmableRun() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures.student(academyId, "학생1");
        fixtures.verifiedAddress(student, firstStop, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(1);
        return fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, confirmAt.plusMinutes(30),
                confirmAt);
    }

    private long escort(long academyId, String name) {
        return managerRepository.save(Manager.register(academyId,
                new ManagerProfile(name, "010-0000-0000", ManagerRole.ESCORT, ALL_DAY_MONDAY))).getId();
    }

    private long academyOf(long runId) {
        return runRepository.findById(runId).orElseThrow().getAcademyId();
    }

    @Test
    @DisplayName("동승자 자리가 빈 회차는 확정 배치가 근무 가능한 동승자를 배정하고 assignment_changed 를 낸다")
    void 빈_동승자_자리는_확정_배치가_채운다() {
        long runId = confirmableRun();
        long escortId = escort(academyOf(runId), "자동동승자");

        confirmationService.confirmOne(runId);

        assertThat(assignmentRepository.findByRunIdAndRole(runId, ManagerRole.ESCORT))
                .as("동승자 없는 회차가 출발하면 승하차 기록 주체가 없다(C-06)")
                .map(Assignment::getManagerId).contains(escortId);
        assertThat(applicationEvents.stream(AssignmentChangedEvent.class))
                .extracting(AssignmentChangedEvent::managerId).containsExactly(escortId);
    }

    @Test
    @DisplayName("이미 수동 배치된 동승자는 건드리지 않는다")
    void 수동_배치된_동승자는_그대로다() {
        long runId = confirmableRun();
        long academyId = academyOf(runId);
        long manualId = escort(academyId, "수동동승자");
        escort(academyId, "다른동승자");
        assignmentRepository.save(Assignment.uponAssignment(runId, manualId, ManagerRole.ESCORT,
                OffsetDateTime.now(clock), 1L));

        confirmationService.confirmOne(runId);

        assertThat(jdbcTemplate.queryForList("SELECT manager_id FROM assignment WHERE run_id = ? AND role = 'escort'",
                Long.class, runId)).containsExactly(manualId);
        assertThat(applicationEvents.stream(AssignmentChangedEvent.class)).isEmpty();
    }
}
