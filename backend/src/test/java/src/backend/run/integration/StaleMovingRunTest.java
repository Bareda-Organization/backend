package src.backend.run.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.proximity.ProximityFixtures;
import src.backend.observability.scheduler.StaleMovingRunGaugeScheduler;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * R46-KFIXBE K-1(Ruling 701·702) — 운행일이 어제보다 이른 채 끝나지 않은 이동 중 회차는 ① 노선 잠금({@code existsOnMovingRun})에서
 * 빠지고 ② {@code schoolbus.run.moving.stale} 게이지로 센다. 어제 운행일(자정을 넘겨 달리는 회차)은 여전히 잠금 대상이고 지표에 안 든다.
 * 근접 판정·유실 집계가 같은 범위를 쓰는지는 각 스케줄러 시험이 본다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 스케줄러가 별도 트랜잭션으로 읽는다. 다른 시험이 남긴 이동 중 회차가 있을 수 있어
 * 지표는 절대값이 아니라 <b>이 시험이 심기 전후의 차이</b>로 본다.
 */
@SpringBootTest
class StaleMovingRunTest {

    private static final String STOP_LNG = "127.000000";

    @Autowired
    private StaleMovingRunGaugeScheduler gaugeScheduler;

    @Autowired
    private MovingRunWindowPolicy windowPolicy;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @AfterEach
    void tearDown() {
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    @Test
    void 끝나지_않은_회차_지표는_이틀_전_이전_운행일만_센다() {
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        LocalDate today = LocalDate.now(clock);
        double before = staleGauge();

        movingRunOn(fx, academyId, today.minusDays(1));
        movingRunOn(fx, academyId, today);
        assertThat(staleGauge()).as("어제·오늘 운행일 회차는 세지 않는다").isEqualTo(before);

        movingRunOn(fx, academyId, today.minusDays(2));
        assertThat(staleGauge()).as("이틀 전 운행일 회차 1건").isEqualTo(before + 1);
    }

    @Test
    void 노선_잠금은_이틀_전_회차를_보지_않고_어제_회차는_본다() {
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        LocalDate today = LocalDate.now(clock);
        long staleStop = fx.stop(academyId, "37.500000", STOP_LNG);
        long overnightStop = fx.stop(academyId, "37.510000", STOP_LNG);
        placeOnRoute(fx, movingRunOn(fx, academyId, today.minusDays(2)), staleStop);
        placeOnRoute(fx, movingRunOn(fx, academyId, today.minusDays(1)), overnightStop);

        LocalDate since = windowPolicy.earliestServiceDate();

        assertThat(runStopRepository.existsOnMovingRun(List.of(staleStop), academyId, RunStatus.MOVING, since))
                .as("끝나지 않은 옛 회차가 좌표 수정을 영구히 막으면 안 된다").isFalse();
        assertThat(runStopRepository.existsOnMovingRun(List.of(overnightStop), academyId, RunStatus.MOVING, since))
                .as("자정을 넘겨 달리는 어제 운행일 회차는 여전히 잠근다").isTrue();
    }

    private double staleGauge() {
        gaugeScheduler.refresh();
        return meterRegistry.get("schoolbus.run.moving.stale").gauge().value();
    }

    /** 그 운행일의 이동 중 회차 1건 — 버스를 매번 새로 만들어 {@code uk_run_bus_date_direction_depart} 를 피한다. */
    private long movingRunOn(ProximityFixtures fx, long academyId, LocalDate serviceDate) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long runId = fx.movingRun(academyId, fx.bus(academyId), Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        jdbcTemplate.update("UPDATE run SET service_date = ? WHERE id = ?", serviceDate, runId);
        return runId;
    }

    private void placeOnRoute(ProximityFixtures fx, long runId, long stopId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
    }

    private ProximityFixtures fixtures() {
        return new ProximityFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
    }
}
