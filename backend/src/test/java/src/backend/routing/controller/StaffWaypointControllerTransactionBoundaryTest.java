package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * {@code WaypointCommandService.add()} 의 트랜잭션 경계(§5.15, {@code WaypointStore#saveCandidate}) —
 * "미배포 후보 삭제 + 새 후보 저장" 두 쓰기가 실제로 한 트랜잭션인지 HTTP 왕복으로 실측한다.
 *
 * <p>{@code StaffWaypointControllerTest} 는 클래스 전체가 {@code @Transactional} 이라 이 결함을
 * 가린다 — 테스트가 이미 열어 둔 트랜잭션이 {@code deleteAllUnappliedByRunIdAndAcademyId}
 * ({@code @Modifying} 커스텀 쿼리, 그 자체엔 {@code @Transactional} 이 없다)에 얹혀 돈다. 실제 운영
 * HTTP 요청 스레드는 그런 바깥 트랜잭션이 없으므로, <b>이 클래스는 일부러 {@code @Transactional} 을
 * 빼서</b> 그 조건을 재현한다({@code RouteRegistrationConcurrencyTest} 와 같은 이유). 대신 만든 행은
 * {@link #뒷정리한다()} 가 직접 지운다 — 자동 롤백이 없다.
 *
 * <p>회차를 <b>미확정</b>으로 둔 것은 최소 재현을 위해서다 — {@code saveCandidate} 는
 * {@code routeContextOf}(확정 노선 조회)보다 먼저 불리므로, 확정 배치·지도 API 계산 없이도 이 결함을
 * 그대로 겪는다. 결함이 있으면(트랜잭션 미보호) 저장까지 가지 못해 {@code 500}, 고쳤으면 저장은
 * 성공하고 그 다음 단계에서 {@code 409 RUN_NOT_CONFIRMED} 로 넘어간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffWaypointControllerTransactionBoundaryTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private RunConfirmationFixtures fixtures;

    private Long academyId;

    private Long runId;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    /**
     * 이 클래스가 만든 행을 전부 지운다 — {@code @Transactional} 이 없어 자동 롤백이 안 된다. 회차가
     * 미확정이라 {@code confirmed_route}·{@code route_version} 은 애초에 생기지 않지만, 배포까지
     * 가는 시나리오로 바뀔 가능성을 대비해 함께 지운다.
     */
    @AfterEach
    void 뒷정리한다() {
        if (academyId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM waypoint WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)",
                academyId);
        jdbcTemplate.update("DELETE FROM run_stop WHERE route_version_id IN "
                + "(SELECT id FROM route_version WHERE confirmed_route_id IN "
                + "(SELECT run_id FROM confirmed_route WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)))",
                academyId);
        jdbcTemplate.update("DELETE FROM confirmed_route WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)",
                academyId);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM route_stop WHERE route_id IN (SELECT id FROM route WHERE academy_id = ?)",
                academyId);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
    }

    /**
     * 트랜잭션 경계가 없으면(결함) {@code deleteAllUnappliedByRunIdAndAcademyId} 에서
     * {@code TransactionRequiredException} 이 나 {@code 500} 이 나가고 저장까지 못 간다. 고쳤으면
     * 저장은 성공하고(행 1개가 실제로 커밋됨) 그다음 {@code routeContextOf} 가 미확정 회차를 걸러
     * {@code 409 RUN_NOT_CONFIRMED} 로 응답한다 — 목표 4 의 완료 조건이 POST 경로에서도 성립함을
     * 이 단언이 고정한다.
     */
    @Test
    void 미확정_회차에_경유_지점을_지정해도_저장_쓰기가_커밋되고_409_로_응답한다() throws Exception {
        시나리오를_만든다();

        mockMvc.perform(post("/api/v1/staff/runs/" + runId + "/waypoints")
                .header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(경유_본문("트랜잭션검증로 123", "트랜잭션 경계 검증")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM waypoint WHERE run_id = ? AND applied = false", Integer.class, runId))
                .as("saveCandidate 의 저장 쓰기가 실제로 커밋됐어야 한다 — 트랜잭션 경계가 없으면 "
                        + "삭제 쿼리에서 TransactionRequiredException 이 나 저장까지 가지 못한다")
                .isEqualTo(1);
    }

    private void 시나리오를_만든다() {
        academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures().stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures().stop(academyId, "37.564000", "126.974000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(ZoneOffset.of("+09:00"));
        runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    private String 경유_본문(String address, String label) {
        return "{\"address\":\"" + address + "\",\"label\":\"" + label + "\",\"apply\":false}";
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
