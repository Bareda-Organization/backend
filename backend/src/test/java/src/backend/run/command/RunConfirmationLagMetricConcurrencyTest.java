package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import io.micrometer.core.instrument.Timer;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 배치 지연 지표(목표 8)의 동시성 — 같은 회차를 두 스레드가 동시에 확정 시도해도 지표 표본이
 * <b>확정 사건 수(1)</b>만큼만 늘어나는지를 본다(게이트 리뷰 Important 1 항, 재현본).
 *
 * <p>{@link RunConfirmationPersistence#persist} 는 {@code confirmIfIdle} 이 0행을 갱신하면(진 스레드)
 * 조용히 반환한다 — 반환값이 없던 시절엔 이 반환도 {@code confirmOne} 의 계측 호출까지 그대로
 * 도달해, 확정 사건은 1건인데 표본은 2개 쌓였다(리뷰어 스크래치 시험으로 실측된 결함). 이 클래스는
 * 그 재현을 이 저장소의 정식 산출물로 남긴다.
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — 붙이면 테스트 스레드가 트랜잭션을 쥔 채 두 워커
 * 스레드를 부르는 꼴이 되어, {@code confirmIfIdle} 의 조건부 UPDATE 가 서로의 커밋을 보지 못하고
 * 동시성 자체가 무력화된다({@code StopMergeConcurrencyTest} 와 같은 근거).
 *
 * <p>{@code confirm_at} 을 고정 시계 기준 과거로 심는다 — {@code RunConfirmationLagMetricTest} 와
 * 같은 이유로, confirm_at 이 미래거나 현재와 같으면 지연 값이 0에 가까워 "표본이 부풀었다"는 실패가
 * 델타 계산 오차와 구분되지 않을 수 있다.
 */
@SpringBootTest
class RunConfirmationLagMetricConcurrencyTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final String LAG_METRIC = "schoolbus.run.confirmation.lag";

    /** 두 스레드가 출발선에서 모이는 상한 — 정상 흐름에서는 소진되지 않는다. */
    private static final long WAIT_LIMIT_SECONDS = 20;

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
    private Clock clock;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** {@code RunConfirmationSchedulerTest} 와 같은 정리 — 이 클래스도 실제 커밋을 남긴다. */
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

    private Timer currentTimerOrNull() {
        return registry.find(LAG_METRIC).timer();
    }

    private long countOf(Timer timer) {
        return timer == null ? 0 : timer.count();
    }

    @Test
    @DisplayName("목표8-동시성 — 같은 회차를 두 스레드가 동시에 확정해도 지표 표본은 1건만 늘어난다")
    void 같은_회차를_동시에_확정해도_표본은_1건만_늘어난다() throws Exception {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures.student(academyId, "학생1");
        fixtures.verifiedAddress(student, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");

        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(5);
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);

        long countBefore = countOf(currentTimerOrNull());

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> confirmAfterBarrier(startLine, runId));
            Future<?> second = pool.submit(() -> confirmAfterBarrier(startLine, runId));
            first.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }

        long countAfter = countOf(currentTimerOrNull());
        assertThat(countAfter - countBefore)
                .as("확정 사건은 1건이니 표본도 1개만 늘어야 한다 — 2면 진 스레드도 함께 기록된 것이다")
                .isEqualTo(1);
        assertThat(runRepository.findById(runId).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    }

    /** 두 스레드가 출발선에서 모인 뒤 곧바로 같은 회차를 확정 시도한다. */
    private Void confirmAfterBarrier(CyclicBarrier startLine, long runId) {
        try {
            startLine.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
        confirmationService.confirmOne(runId);
        return null;
    }
}
