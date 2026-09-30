package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.entity.WorkHours;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.assign.spec.AttendantAssigner;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * BR-201 — 같은 학원·같은 출발 시각 회차 두 개가 병렬 확정 스레드에서 동시에 동승자를 고르면, 서로의 미커밋
 * 배정을 못 봐 같은 동승자가 둘 다에 배정되던 결함의 재현본이다.
 *
 * <p>경합 창은 배정기({@link AttendantAssigner#assign}) 진입 지점에 배리어를 세워 강제한다 — 두 스레드가 후보의 겹침
 * 창을 읽은 <b>뒤</b> 둘 다 배정기에 닿아야 서로 배정하기 전 상태를 본 것이다. 학원 단위 직렬화가 있으면 뒤 스레드가
 * 앞 스레드의 커밋을 기다리므로 배리어는 시간 만료로 풀리고, 뒤 스레드는 커밋된 배정을 읽어 후보를 거른다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunConfirmationAttendantConcurrencyTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final WorkHours ALL_DAY_MONDAY = WorkHours.of(
            Map.of("mon", List.of(Map.of("start", "00:00", "end", "23:59"))));

    /** 직렬화가 있을 때 앞 스레드가 배리어에서 기다리는 상한 — 이 시간이 지나면 배리어를 깨고 그대로 진행한다. */
    private static final long BARRIER_LIMIT_SECONDS = 3;

    private static final long WAIT_LIMIT_SECONDS = 60;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private ManagerRepository managerRepository;

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

    @MockitoSpyBean
    private AttendantAssigner attendantAssigner;

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
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN (SELECT id FROM run WHERE academy_id IN "
                + academyIds + ")");
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    /** 그 학원의 버스·노선·학생이 딸린 확정 가능 회차 하나 — 출발 시각은 호출자가 정한다. */
    private long confirmableRun(long academyId, OffsetDateTime departTime, String studentName) {
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures.student(academyId, studentName);
        fixtures.verifiedAddress(student, firstStop, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        return fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    @Test
    @DisplayName("BR-201 — 같은 학원·같은 출발 시각 회차 두 개를 동시에 확정해도 동승자 한 명은 한 회차에만 배정된다")
    void 같은_시각_회차_둘을_동시에_확정해도_동승자는_한_회차에만_배정된다() throws Exception {
        long academyId = fixtures.academyWithCoordinates();
        Manager escort = managerRepository.save(Manager.register(academyId,
                new ManagerProfile("유일동승자", "010-0000-0000", ManagerRole.ESCORT, ALL_DAY_MONDAY)));
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(30);
        long firstRun = confirmableRun(academyId, departTime, "학생1");
        long secondRun = confirmableRun(academyId, departTime, "학생2");

        // 두 스레드가 겹침 창을 읽은 뒤 배정기에 함께 닿게 한다 — 직렬화가 있으면 한 쪽이 못 와서 시간 만료로 풀린다.
        CyclicBarrier bothReadWindows = new CyclicBarrier(2);
        doAnswer(invocation -> {
            try {
                bothReadWindows.await(BARRIER_LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception 시간_만료_또는_배리어_깨짐) {
                // 직렬화된 쪽 — 상대가 오지 못했으니 그대로 진행한다.
            }
            return invocation.callRealMethod();
        }).when(attendantAssigner).assign(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> confirmationService.confirmOne(firstRun));
            Future<?> second = pool.submit(() -> confirmationService.confirmOne(secondRun));
            first.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForList("SELECT run_id FROM assignment WHERE manager_id = ? AND role = 'escort'",
                Long.class, escort.getId()))
                .as("같은 시각에 출발하는 두 회차를 한 동승자가 동시에 탈 수 없다 — 2건이면 서로의 미커밋 배정을 못 본 것이다")
                .hasSize(1);
    }
}
