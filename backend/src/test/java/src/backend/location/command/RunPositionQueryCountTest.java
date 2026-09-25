package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * BR-100 — 위치 1건(2초마다)이 커밋된 뒤 리스너 둘(Redis 최신 좌표 · WebSocket 방송)이 같은 정차 목록을 각자
 * 다시 읽었다. 정차 목록은 도착 처리 때만 바뀌는 값이라, 요청 1건에 한 번만 읽으면 된다.
 *
 * <p>커밋 뒤 리스너가 돌아야 하므로 {@code @Transactional} 을 쓰지 않는다 — 만든 행과 Redis 키는 직접 지운다.
 * 셈은 Hibernate 통계의 "그 조회문이 실행된 횟수" 로 한다(다른 시험과 섞이지 않게 요청 전후 차이만 본다).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class RunPositionQueryCountTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ConfirmedRouteRepository confirmedRouteRepository;
    @Autowired private RouteVersionRepository routeVersionRepository;
    @Autowired private RunStopRepository runStopRepository;
    @Autowired private RunRiderRepository runRiderRepository;
    @Autowired private AcademyStaffRepository academyStaffRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;

    private final List<Long> academyIds = new ArrayList<>();

    private final List<Long> runIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)",
                    academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        runIds.forEach(runId -> stringRedisTemplate.delete("run:" + runId + ":position"));
        academyIds.clear();
        runIds.clear();
    }

    @Test
    @DisplayName("BR-100 — 위치 1건에 정차 목록 조회는 한 번이다(두 리스너가 각자 다시 읽지 않는다)")
    void 위치_1건에_정차_목록_조회는_한_번이다() throws Exception {
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        runIds.add(runId);
        fx.startRun(runId, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now);
        fx.runStopForDestination(versionId, 2);
        jdbcTemplate.update("UPDATE run_stop SET arrived_at = now() WHERE id = ?", runStopId);
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = 정차_목록_조회_횟수(statistics);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(driverAccountId,
                                academyId, Role.DRIVER, AccountStatus.ACTIVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat": 37.561000, "lng": 126.971000, "recorded_at": "%s"}
                                """.formatted(now.minusSeconds(2))))
                .andExpect(status().isNoContent());

        assertThat(정차_목록_조회_횟수(statistics) - before)
                .as("정차 목록(RunStopRepository#findAllByRouteVersionIdAndAcademyIdOrderBySeq)을 위치 1건에 두 번 읽으면 안 된다")
                .isEqualTo(1);
    }

    /** 정차 목록 조회문(학원 범위 이중 부모 조인)의 누적 실행 횟수 — 문장 원문으로 찾는다. */
    private long 정차_목록_조회_횟수(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("FROM RunStop rs") && query.contains("rv.confirmedRouteId")
                        && query.contains("ORDER BY rs.seq ASC") && !query.contains("rs.stopId IS NOT NULL"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }
}
