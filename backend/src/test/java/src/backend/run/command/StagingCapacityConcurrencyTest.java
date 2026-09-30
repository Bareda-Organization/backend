package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.dto.ForcedAdditionRequest;
import src.backend.run.dto.NewStudentRequest;
import src.backend.run.dto.TransferRequest;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import src.backend.student.service.AddressVerification;
import testsupport.clock.FixedClock20260826T02Config;

/**
 * BR-206 — 강제 추가(§5.7)·버스 간 이동(§5.8)의 정원 판정이 회차 잠금 밖에서 끝나, 정원 1자리 남은 회차에 두 요청이
 * 동시에 들어오면 둘 다 통과해 예정 명단이 정원을 넘던 결함의 재현본이다.
 *
 * <p>경합 창은 정원 판정과 저장 사이에 있는 주소 검증({@link AddressVerification#verifySingle}) 호출에 배리어를 세워
 * 강제한다 — 두 요청이 <b>둘 다 정원 판정을 통과한 뒤에야</b> 저장으로 넘어간다. 저장 안에서(회차 잠금 뒤) 정원을 다시
 * 세면 늦은 쪽이 {@code 409 CAPACITY_EXCEEDED} 를 받는다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import(FixedClock20260826T02Config.class)
class StagingCapacityConcurrencyTest {

    private static final long STAFF_ACCOUNT_ID = 9301L;

    private static final long BARRIER_LIMIT_SECONDS = 5;

    private static final long WAIT_LIMIT_SECONDS = 60;

    private static final String ADDRESS = "테스트로 100";

    @Autowired
    private ForcedAdditionCommandService forcedAdditionCommandService;

    @Autowired
    private TransferCommandService transferCommandService;

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
    private AddressVerification addressVerification;

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
        String runIds = "(SELECT id FROM run WHERE academy_id IN " + academyIds + ")";
        jdbcTemplate.update("DELETE FROM run_transfer WHERE from_run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run_forced_addition WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    /** 두 요청이 정원 판정을 통과한 뒤 주소 검증에서 만난 다음에야 저장으로 넘어가게 한다. */
    private void holdBothRequestsBeforeStaging() {
        CyclicBarrier bothPassedCapacityCheck = new CyclicBarrier(2);
        doAnswer(invocation -> {
            try {
                bothPassedCapacityCheck.await(BARRIER_LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception 시간_만료_또는_배리어_깨짐) {
                // 한 요청만 왔다 — 상대를 더 기다리지 않고 진행한다.
            }
            return invocation.callRealMethod();
        }).when(addressVerification).verifySingle(anyString());
    }

    /** 두 호출을 동시에 띄워 성공 수와 실패 사유를 돌려준다. */
    private Outcome runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(pool.submit(first), pool.submit(second));
            int succeeded = 0;
            int capacityExceeded = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof BusinessException business
                            && business.getErrorCode() == ErrorCode.CAPACITY_EXCEEDED) {
                        capacityExceeded++;
                    } else {
                        throw e;
                    }
                }
            }
            return new Outcome(succeeded, capacityExceeded);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private record Outcome(int succeeded, int capacityExceeded) {
    }

    private OffsetDateTime departTime() {
        return OffsetDateTime.now(clock).plusMinutes(31);
    }

    /** 학생 정원 1석인 버스. */
    private long oneSeatBus(long academyId) {
        return busRepository.save(Bus.register(academyId, "정원시험" + System.nanoTime(), "00가0000",
                new BusSeating(3, 1, 1))).getId();
    }

    private long idleRun(long academyId, long busId) {
        OffsetDateTime depart = departTime();
        return fixtures.idleRun(academyId, busId, depart.toLocalDate(), Direction.TO_ACADEMY, depart,
                depart.minusMinutes(30));
    }

    @Test
    @DisplayName("BR-206 — 정원 1자리 남은 회차에 강제 추가 두 건이 동시에 와도 한 건만 저장되고 나머지는 409 CAPACITY_EXCEEDED 다")
    void 강제_추가_두_건이_동시에_와도_정원을_넘기지_않는다() throws Exception {
        long academyId = fixtures.academyWithCoordinates();
        long runId = idleRun(academyId, oneSeatBus(academyId));
        AuthUser staff = new AuthUser(STAFF_ACCOUNT_ID, academyId, Role.STAFF, AccountStatus.ACTIVE);
        holdBothRequestsBeforeStaging();

        Outcome outcome = runConcurrently(
                () -> forcedAdditionCommandService.add(staff, runId,
                        new ForcedAdditionRequest(null, new NewStudentRequest("학생1"), ADDRESS, null)),
                () -> forcedAdditionCommandService.add(staff, runId,
                        new ForcedAdditionRequest(null, new NewStudentRequest("학생2"), ADDRESS, null)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_forced_addition WHERE run_id = ?",
                Integer.class, runId))
                .as("정원 1석 회차의 예정 명단이 2명이 되면 확정 뒤 초과 인원이 그대로 탄다")
                .isEqualTo(1);
        assertThat(outcome).isEqualTo(new Outcome(1, 1));
    }

    @Test
    @DisplayName("BR-206 — 정원 1자리 남은 도착 회차로 이동 두 건이 동시에 와도 한 건만 저장되고 나머지는 409 CAPACITY_EXCEEDED 다")
    void 이동_두_건이_동시에_와도_정원을_넘기지_않는다() throws Exception {
        long academyId = fixtures.academyWithCoordinates();
        long toRunId = idleRun(academyId, oneSeatBus(academyId));
        long firstStudent = studentOnNewRun(academyId, "이동학생1");
        long secondStudent = studentOnNewRun(academyId, "이동학생2");
        AuthUser staff = new AuthUser(STAFF_ACCOUNT_ID, academyId, Role.STAFF, AccountStatus.ACTIVE);
        holdBothRequestsBeforeStaging();

        Outcome outcome = runConcurrently(
                () -> transferCommandService.transfer(staff, firstStudent,
                        new TransferRequest(fromRunOf(firstStudent), toRunId, null, ADDRESS, null)),
                () -> transferCommandService.transfer(staff, secondStudent,
                        new TransferRequest(fromRunOf(secondStudent), toRunId, null, ADDRESS, null)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_transfer WHERE to_run_id = ?",
                Integer.class, toRunId))
                .as("정원 1석 회차의 예정 명단이 2명이 되면 확정 뒤 초과 인원이 그대로 탄다")
                .isEqualTo(1);
        assertThat(outcome).isEqualTo(new Outcome(1, 1));
    }

    /** 자기 버스·노선·회차를 가진 학생 — 그 회차의 예정 명단에 요일별 주소로 들어 있다. */
    private long studentOnNewRun(long academyId, String name) {
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime depart = departTime();
        Weekday weekday = Weekday.of(depart.toLocalDate());
        fixtures.route(academyId, busId, weekday, Direction.TO_ACADEMY, stopId);
        long studentId = fixtures.student(academyId, name);
        fixtures.verifiedAddress(studentId, stopId, weekday, Direction.TO_ACADEMY, "37.560000", "126.970000");
        idleRun(academyId, busId);
        return studentId;
    }

    /** 그 학생이 요일별 주소로 속한 출발 회차 — {@link #studentOnNewRun} 이 만든 회차다. */
    private long fromRunOf(long studentId) {
        return jdbcTemplate.queryForObject("SELECT r.id FROM run r JOIN route ro ON ro.bus_id = r.bus_id "
                + "JOIN route_stop rs ON rs.route_id = ro.id JOIN weekly_address wa ON wa.stop_id = rs.stop_id "
                + "WHERE wa.student_id = ?", Long.class, studentId);
    }
}
