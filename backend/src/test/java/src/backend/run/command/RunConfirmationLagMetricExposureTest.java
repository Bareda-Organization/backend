package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

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
 * 배치 지연 지표가 실제 {@code /actuator/prometheus} 경로에 노출되는지를 본다(Phase 7 목표 8).
 *
 * <p>{@code ./gradlew bootRun} 은 고정 포트 8080 을 다른 태스크와 공유해 쓸 수 없다(브리프 경계) —
 * 대신 {@code PrometheusEndpointTest} 와 같은 근거로 {@code RANDOM_PORT} 실서버 + {@code TestRestTemplate}
 * 을 쓴다. {@code MockMvc}(웹 슬라이스)는 이 지표 자체엔 문제가 없지만, 이 저장소가 이미 실서버 방식을
 * 검증된 선례로 갖고 있어 그 쪽을 재사용하는 것이 새 방식을 들이는 것보다 위험이 작다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class RunConfirmationLagMetricExposureTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

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

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("목표8 — 확정 1건 이후 /actuator/prometheus 응답에 지표 이름이 실제로 나타난다")
    void 확정_후_지표_이름이_프로메테우스_응답에_노출된다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
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

        confirmationService.confirmOne(runId);

        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Micrometer 는 Prometheus 노출 형식에서 '.'을 '_'로 바꾸고 Timer 는 _seconds_count·_seconds_sum
        // 접미사를 붙인다 — 이름이 실제로 그 형태로 나타나는지까지 확인해야 "노출됐다"는 단언이 된다.
        assertThat(response.getBody()).contains("schoolbus_run_confirmation_lag_seconds_count");
    }
}
