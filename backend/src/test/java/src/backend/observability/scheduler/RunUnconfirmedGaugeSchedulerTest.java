package src.backend.observability.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

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

/**
 * {@code schoolbus.run.unconfirmed} 게이지(관측 목표 8)가 <b>값</b>까지 맞게 세는지 본다 —
 * {@link src.backend.observability.MetricsExposureTest} 는 이름의 존재만 보고 값은 보지 않는다.
 *
 * <p>문턱은 {@code confirm_at + 5분} 이 지난 {@code idle} 회차다({@link RunUnconfirmedGaugeScheduler}
 * javadoc). 아래 4건을 같은 배치에 섞어 넣어, 문턱을 살짝 넘긴 것과 살짝 못 미친 것 · idle 이 아닌 것 ·
 * 취소된 것을 한 카운트로 구별한다.
 *
 * <p>절대값이 아니라 <b>증분</b>으로 단언한다 — 이 게이지는 전 학원 대상 집계라(범위를 좁힐 수 없는
 * 이유는 {@code RunRepository} 의 {@code @AcademyScopeExempt} 근거 참고) 로컬 시드
 * ({@code V2__seed_data.sql})가 심어 둔 idle 회차 1건이 이미 실측 문턱을 넘겨 있다. 문턱 전후로 이
 * 시험이 심은 4건만큼만 값이 얼마나 늘었는지를 보면 그 시드 오염과 무관하게 판정할 수 있다.
 */
@SpringBootTest
class RunUnconfirmedGaugeSchedulerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    @Autowired
    private RunUnconfirmedGaugeScheduler scheduler;

    @Autowired
    private MeterRegistry meterRegistry;

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

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

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
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    private double gaugeValue() {
        return meterRegistry.get("schoolbus.run.unconfirmed").gauge().value();
    }

    @Test
    @DisplayName("문턱을 넘긴 idle 회차만 센다 — 5분 미만 지연·타 상태·취소분은 제외한다")
    void 문턱을_넘긴_idle_회차만_게이지에_반영된다() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        scheduler.refresh();
        double before = gaugeValue();

        // depart_time 은 confirm_at + 30분으로 고정된다(DB CHECK ck_run_confirm_at) — 그래서 회차마다
        // confirm_at 자체를 분 단위로 갈라, (bus_id, depart_time) UNIQUE 도 함께 자연히 피한다.

        // 대상 — confirm_at 이 6분 지난 idle 회차.
        long overdueRunId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                now.minusMinutes(6).plusMinutes(30), now.minusMinutes(6));

        // 제외 1 — confirm_at 이 2분만 지나 아직 5분 여유 안이다(확정 배치 대상일 수는 있어도 경보 대상은 아니다).
        fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                now.minusMinutes(2).plusMinutes(30), now.minusMinutes(2));

        // 제외 2 — confirm_at 은 7분 지났지만 이미 confirmed 라 idle 이 아니다.
        long confirmedRunId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                now.minusMinutes(7).plusMinutes(30), now.minusMinutes(7));
        jdbcTemplate.update("UPDATE run SET status = 'confirmed' WHERE id = ?", confirmedRunId);

        // 제외 3 — confirm_at 은 8분 지났고 상태는 idle 이지만 취소됐다.
        long canceledRunId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                now.minusMinutes(8).plusMinutes(30), now.minusMinutes(8));
        jdbcTemplate.update("UPDATE run SET canceled_at = ? WHERE id = ?", now, canceledRunId);

        scheduler.refresh();

        assertThat(gaugeValue() - before).as("대상 1건(" + overdueRunId + ")만큼만 늘어야 한다").isEqualTo(1.0);
    }
}
