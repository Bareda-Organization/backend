package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * {@code schoolbus.run.confirmed.not_started} 게이지는 <b>확정됐는데 시작 가능 시간창(출발 + 10분)이 닫히도록 시작하지 않은</b> 회차를 센다 —
 * 노선을 받은 기사가 출발하지 않은 채 남은 회차를 드러내는 유일한 신호다. 시간창 안의 회차 · 다른 상태 · 취소된 회차 · 운행일이 지난 회차는 세지 않는다.
 *
 * <p>대상을 2건으로 둬 상태 조건이 바뀌어도 증분이 우연히 같아지지 않게 하고, 절대값이 아니라 증분으로 단언한다(전 학원 대상 집계라 시드 행이 섞인다).
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunConfirmedNotStartedGaugeSchedulerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    private static final String METRIC = "schoolbus.run.confirmed.not_started";

    @Autowired private RunConfirmedNotStartedGaugeScheduler scheduler;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

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

    private double gaugeValue() {
        return meterRegistry.get(METRIC).gauge().value();
    }

    private long run(long academyId, long busId, LocalDate serviceDate, OffsetDateTime depart, String status) {
        long runId = fixtures.idleRun(academyId, busId, serviceDate, Direction.TO_ACADEMY, depart, depart.minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET status = ? WHERE id = ?", status, runId);
        return runId;
    }

    @Test
    @DisplayName("시작 가능 시간창이 닫혔는데 confirmed 로 남은 회차만 센다")
    void 시간창이_닫힌_confirmed_회차만_센다() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        scheduler.refresh();
        double before = gaugeValue();

        run(academyId, busId, SERVICE_DATE, now.minusMinutes(11), "confirmed");
        run(academyId, busId, SERVICE_DATE, now.minusMinutes(15), "confirmed");
        run(academyId, busId, SERVICE_DATE, now.minusMinutes(10), "confirmed");
        run(academyId, busId, SERVICE_DATE, now.minusMinutes(9), "confirmed");
        run(academyId, busId, SERVICE_DATE, now.minusMinutes(12), "idle");
        long moving = run(academyId, busId, SERVICE_DATE, now.minusMinutes(13), "confirmed");
        jdbcTemplate.update("UPDATE run SET status = 'moving', started_at = ? WHERE id = ?", now.minusMinutes(12), moving);
        long canceled = run(academyId, busId, SERVICE_DATE, now.minusMinutes(14), "confirmed");
        jdbcTemplate.update("UPDATE run SET canceled_at = ? WHERE id = ?", now, canceled);
        run(academyId, busId, SERVICE_DATE.minusDays(1), now.minusDays(1).minusMinutes(20), "confirmed");

        scheduler.refresh();

        assertThat(gaugeValue() - before).as("창이 닫힌 confirmed 2건만큼만 늘어야 한다").isEqualTo(2.0);
    }
}
