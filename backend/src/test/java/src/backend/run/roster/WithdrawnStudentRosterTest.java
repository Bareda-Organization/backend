package src.backend.run.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.entity.Run;
import src.backend.run.query.StaffRunRouteQueryService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * BR-234 — 퇴원생 제외(BR-003)를 <b>호출부가 운행일 0시를 넘기는지</b> 진입점에서 물는다.
 *
 * <p>{@code WeeklyAddressRepositoryTest} 는 저장소 메서드에 인자를 직접 넣어 보므로, 호출부가
 * {@code serviceDayStart} 를 빼먹거나 엉뚱한 시각을 넘기는 결함은 잡지 못한다. 여기서는 확정 배치가 쓰는
 * {@link ProjectedRosterReader} 와 예정 경로를 만드는 {@link StaffRunRouteQueryService} 두 호출부가 각자
 * "운행일 전 퇴원은 빼고 당일 퇴원은 남긴다" 를 지키는지 본다. 운행일 2030-04-01(월)은 고정 시계의 날짜다.
 */
@SpringBootTest
@Transactional
@Import(FixedClock20300401Config.class)
class WithdrawnStudentRosterTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    private static final OffsetDateTime SERVICE_DAY_START = OffsetDateTime.parse("2030-04-01T00:00:00+09:00");

    @Autowired private ProjectedRosterReader rosterReader;
    @Autowired private StaffRunRouteQueryService staffRunRouteQueryService;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long academyId;
    private long stopId;
    private long activeStudent;
    private long withdrawnEarlier;
    private long withdrawnToday;
    private Run run;

    @BeforeEach
    void 세_학생과_회차를_심는다() {
        RunConfirmationFixtures fx = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        stopId = fx.stop(academyId, "37.510000", "127.010000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        activeStudent = fx.student(academyId, "재원생");
        withdrawnEarlier = fx.student(academyId, "전날퇴원");
        withdrawnToday = fx.student(academyId, "당일퇴원");
        for (long studentId : new long[] {activeStudent, withdrawnEarlier, withdrawnToday}) {
            fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.510000", "127.010000");
        }
        withdraw(withdrawnEarlier, SERVICE_DAY_START.minusMinutes(1));
        withdraw(withdrawnToday, SERVICE_DAY_START.plusHours(10));
        OffsetDateTime depart = SERVICE_DAY_START.plusHours(15);
        run = runRepository.findById(fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, depart,
                depart.minusMinutes(30))).orElseThrow();
    }

    @Test
    @DisplayName("BR-234 — 확정 배치의 명단 읽기는 운행일 전 퇴원생만 빼고 당일 퇴원생은 남긴다")
    void 명단_읽기는_운행일_전_퇴원생만_뺀다() {
        assertThat(rosterReader.read(run).studentIds())
                .as("운행일 0시 전 퇴원생이 명단에 들어왔거나, 당일 퇴원생이 빠졌다 — 호출부가 serviceDayStart 를 잘못 넘긴다")
                .containsExactlyInAnyOrder(activeStudent, withdrawnToday);
    }

    @Test
    @DisplayName("BR-234 — 예정 경로의 정차지 인원도 운행일 전 퇴원생을 세지 않는다")
    void 예정_경로_인원은_운행일_전_퇴원생을_세지_않는다() {
        var response = staffRunRouteQueryService.route(
                new AuthUser(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE), run.getId());

        assertThat(response.stops()).hasSize(1);
        assertThat(response.stops().getFirst().studentCount())
                .as("재원생 + 당일 퇴원생 2명이어야 한다 — 전날 퇴원생이 세어졌거나 당일 퇴원생이 빠졌다")
                .isEqualTo(2L);
    }

    private void withdraw(long studentId, OffsetDateTime at) {
        jdbcTemplate.update("UPDATE student SET deleted_at = ? WHERE id = ?", Timestamp.from(at.toInstant()),
                studentId);
    }
}
