package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

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

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 배치 스케줄러({@link RunConfirmationScheduler#confirmDueRuns}) 수준의 검증(Phase 7 목표
 * 3 · 4 · 6) — {@code RunConfirmationServiceTest} 가 회차 1건의 결과물을 보는 것과 달리, 이 클래스는
 * <b>여러 회차 중 무엇을 언제 얼마나 집는지</b>를 본다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@link RunConfirmationScheduler#confirmDueRuns} 가
 * 실제 확정 작업을 {@code runConfirmationExecutor} 의 별도 워커 스레드로 넘기므로({@code
 * CompletableFuture.runAsync}), 테스트 스레드에 묶인 트랜잭션 롤백은 그 워커가 커밋한 것을 되돌리지
 * 못한다({@code RunGenerationServiceTest} 와 같은 근거). 뒷정리는 {@link RunConfirmationFixtures#ACADEMY_NAME}
 * 로 표시된 행을 직접 지운다.
 */
@SpringBootTest
class RunConfirmationSchedulerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private RunConfirmationScheduler scheduler;

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

    /**
     * {@link RunConfirmationFixtures#ACADEMY_NAME} 로 표시된 행만 지운다 — 삭제 순서는 FK
     * {@code ON DELETE RESTRICT} 를 거스르지 않는 방향이다({@code run} 삭제가 산출물 4종을 이미
     * 연쇄 삭제한다, V1 스키마).
     */
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

    /** 정상 노선까지 갖춘 학원 1곳 — {@code bus}·{@code route}(2 정차지)·학생 2명까지 완비해 반환한다. */
    private long[] fullyConfiguredAcademyAndBus() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);
        long studentAtFirst = fixtures.student(academyId, "학생1");
        long studentAtLast = fixtures.student(academyId, "학생2");
        fixtures.verifiedAddress(studentAtFirst, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures.verifiedAddress(studentAtLast, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.561000", "126.971000");
        return new long[] { academyId, busId };
    }

    @Test
    @DisplayName("목표3 — 실행 시각이 아니라 저장된 confirm_at 이 대상을 가른다")
    void 판정_시각이_지난_회차만_확정된다() {
        long[] academyAndBus = fullyConfiguredAcademyAndBus();
        long academyId = academyAndBus[0];
        long dueBusId = academyAndBus[1];
        // 아직 노선을 편성하지 않은 별도 버스 — 스케줄러가 실제로 건드리면 ROUTE_NOT_CONFIGURED_FOR_RUN 로
        // 실패해 consecutive_failures 가 올라간다. 만졌는지 여부를 이 값으로 가른다.
        long notDueBusId = fixtures.bus(academyId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime dueConfirmAt = now.minusMinutes(1);
        OffsetDateTime notDueConfirmAt = now.plusMinutes(1);

        long dueRunId = fixtures.idleRun(academyId, dueBusId, SERVICE_DATE, Direction.TO_ACADEMY,
                dueConfirmAt.plusMinutes(30), dueConfirmAt);
        long notDueRunId = fixtures.idleRun(academyId, notDueBusId, SERVICE_DATE, Direction.TO_ACADEMY,
                notDueConfirmAt.plusMinutes(30), notDueConfirmAt);

        scheduler.confirmDueRuns();

        assertThat(runRepository.findById(dueRunId).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
        var notDueRun = runRepository.findById(notDueRunId).orElseThrow();
        assertThat(notDueRun.getStatus().name()).as("판정 시각이 아직이라 손대지 않아야 한다").isEqualTo("IDLE");
        assertThat(notDueRun.getConsecutiveFailures())
                .as("건드렸다면(=버그) 노선 미편성으로 실패해 이 값이 올라간다 — 0이어야 미접근의 증거다").isZero();
    }

    @Test
    @DisplayName("목표4 — 한 회차의 실패가 다른 회차를 막지 않고, 실패 횟수는 재실행에 걸쳐 누적된다")
    void 한_회차의_실패가_다른_회차를_막지_않는다() {
        long[] academyAndBus = fullyConfiguredAcademyAndBus();
        long academyId = academyAndBus[0];
        long goodBusId = academyAndBus[1];
        long badBusId = fixtures.bus(academyId); // 노선 미편성 — 확정 시도 시 반드시 실패한다.

        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(1);
        long goodRunId = fixtures.idleRun(academyId, goodBusId, SERVICE_DATE, Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);
        long badRunId = fixtures.idleRun(academyId, badBusId, SERVICE_DATE, Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);

        scheduler.confirmDueRuns();

        assertThat(runRepository.findById(goodRunId).orElseThrow().getStatus().name())
                .as("옆 회차가 실패해도 이 회차는 확정돼야 한다").isEqualTo("CONFIRMED");
        Integer confirmedRouteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM confirmed_route WHERE run_id = ?", Integer.class, goodRunId);
        assertThat(confirmedRouteCount).isEqualTo(1);

        Integer failuresAfterFirstTick = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM run WHERE id = ?", Integer.class, badRunId);
        String statusAfterFirstTick = jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?",
                String.class, badRunId);
        assertThat(statusAfterFirstTick).as("실패한 회차는 idle 로 남아야 다음 틱에 재시도된다").isEqualTo("idle");
        assertThat(failuresAfterFirstTick).as("실패 기록은 롤백에 휩쓸리지 않고 커밋돼야 한다(REQUIRES_NEW)")
                .isEqualTo(1);

        // 두 번째 틱 — 실패한 회차는 confirm_at 이 그대로라 여전히 대상이고, 확정된 회차는 status 가
        // 바뀌어 더 이상 대상이 아니다(WHERE status = idle).
        scheduler.confirmDueRuns();

        Integer failuresAfterSecondTick = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM run WHERE id = ?", Integer.class, badRunId);
        assertThat(failuresAfterSecondTick).as("재실행에 걸쳐 누적돼야 한다(인메모리가 아니라 행에 저장된 값)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("목표6-a — 한 틱이 집는 회차 수는 BATCH_SIZE 를 넘지 않는다")
    void 한_틱은_배치_크기_상한을_넘지_않는다() {
        long[] academyAndBus = fullyConfiguredAcademyAndBus();
        long academyId = academyAndBus[0];
        long busId = academyAndBus[1];

        OffsetDateTime baseConfirmAt = OffsetDateTime.now(clock).minusHours(1);
        int total = RunConfirmationScheduler.BATCH_SIZE + 1;
        for (int i = 0; i < total; i++) {
            OffsetDateTime confirmAt = baseConfirmAt.plusMinutes(i);
            fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, confirmAt.plusMinutes(30),
                    confirmAt);
        }

        scheduler.confirmDueRuns();

        // 이 버스를 쓰는 회차는 이 51건뿐이다(같은 (bus_id, depart_time) 조합은 UNIQUE 라 겹치지 않는다) —
        // bus_id 로 좁히면 원시 SQL 배열 바인딩 없이도 정확히 이 시험이 만든 대상만 집힌다.
        Integer confirmedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run WHERE bus_id = ? AND status = 'confirmed'", Integer.class, busId);
        Integer idleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE bus_id = ? AND status = 'idle'",
                Integer.class, busId);

        assertThat(confirmedCount).as("설정된 상한(" + RunConfirmationScheduler.BATCH_SIZE + ")을 실측한다")
                .isEqualTo(RunConfirmationScheduler.BATCH_SIZE);
        assertThat(idleCount).as("나머지 1건은 이번 틱에서 손대지 않아 idle 로 남는다").isEqualTo(1);
    }
}
