package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
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
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.SettableClock;
import testsupport.clock.SettableClockConfig;

/**
 * 확정 배치가 회차를 집는 경계 — 판정 시각({@code confirm_at})이 정확히 지금이면 도래분이고 1초 뒤면 아니다. 운행일 비교는 서비스 시간대(Asia/Seoul) 날짜라, 운행일 23:59 KST 에는 그 날의
 * 회차를 집고 다음 날 00:01 KST 에는 지난 운행일의 {@code idle} 회차를 집지 않는다(확정해도 운행할 수 없다).
 */
@SpringBootTest
@Import(SettableClockConfig.class)
class RunConfirmationDueBoundaryTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

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
    @Autowired private SettableClock clock;
    @MockitoSpyBean private RunConfirmationService confirmationService;

    private RunConfirmationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        RunConfirmationFixtures.cleanUpMarkedRows(jdbcTemplate);
        clock.set(Instant.parse("2030-04-01T03:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        RunConfirmationFixtures.cleanUpMarkedRows(jdbcTemplate);
    }

    @Test
    @DisplayName("confirm_at 이 정확히 지금이면 도래분이라 확정된다")
    void 판정_시각이_정확히_지금이면_확정된다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long runId = 회차를_만든다(now);

        scheduler.confirmDueRuns();

        assertThat(상태(runId)).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("confirm_at 이 1초 뒤면 아직 도래하지 않아 손대지 않는다")
    void 판정_시각이_1초_뒤면_집지_않는다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long runId = 회차를_만든다(now.plusSeconds(1));

        scheduler.confirmDueRuns();

        assertThat(상태(runId)).isEqualTo("idle");
        assertThat(실패_횟수(runId)).isZero();
    }

    @Test
    @DisplayName("운행일 23:59 KST 에는 그 날의 도래한 회차를 확정한다")
    void 운행일_자정_직전에는_그날_회차를_집는다() {
        long runId = 회차를_만든다(OffsetDateTime.parse("2030-04-01T23:58:30+09:00"));
        clock.set(Instant.parse("2030-04-01T14:59:00Z"));

        scheduler.confirmDueRuns();

        assertThat(상태(runId)).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("다음 날 00:01 KST 에는 지난 운행일의 idle 회차를 집지 않는다")
    void 자정을_넘으면_지난_운행일_회차를_집지_않는다() {
        long runId = 회차를_만든다(OffsetDateTime.parse("2030-04-01T23:58:30+09:00"));
        clock.set(Instant.parse("2030-04-01T15:01:00Z"));

        scheduler.confirmDueRuns();

        assertThat(상태(runId)).as("지난 날짜의 회차를 확정해도 운행할 수 없다").isEqualTo("idle");
        assertThat(실패_횟수(runId)).isZero();
    }

    /** 정상 노선까지 갖춘 학원의 월요일 등원 회차 — 판정 시각만 호출자가 정한다. */
    private long 회차를_만든다(OffsetDateTime confirmAt) {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures.student(academyId, "학생1");
        fixtures.verifiedAddress(student, firstStop, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, confirmAt.plusMinutes(30),
                confirmAt);
        // 다른 시험이 남긴 도래 회차는 확정하지 않는다 — 이 시험의 회차만 실제로 확정한다
        doAnswer(invocation -> Long.valueOf(runId).equals(invocation.getArgument(0)) ? invocation.callRealMethod() : null)
                .when(confirmationService).confirmOne(anyLong());
        return runId;
    }

    private String 상태(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
    }

    private int 실패_횟수(long runId) {
        return jdbcTemplate.queryForObject("SELECT consecutive_failures FROM run WHERE id = ?", Integer.class, runId);
    }
}
