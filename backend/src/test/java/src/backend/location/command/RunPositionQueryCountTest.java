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
import src.backend.boarding.entity.RiderStatus;
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
            jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
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
    @DisplayName("BR-100 — 위치 1건에 정차 목록(이름 포함) 조회는 한 번이다(두 리스너가 각자 다시 읽지 않는다)")
    void 위치_1건에_정차_목록_조회는_한_번이다() throws Exception {
        Seeded seeded = 회차를_심는다(true);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = 정차_목록_조회_횟수(statistics);

        위치를_보낸다(seeded);

        assertThat(정차_목록_조회_횟수(statistics) - before)
                .as("정차 목록(RunStopRepository#findPositionStops)을 위치 1건에 두 번 읽으면 안 된다")
                .isEqualTo(1);
    }

    /**
     * R46-LATERBE L2(Ruling 671) — 위치 1건(2초마다)의 SQL 은 5건이다: 인가 exists 1(전 2 — 매니저·배치) · 회차 1 · INSERT 1 ·
     * 확정 노선+정차 목록+정차 이름 조인 1(전 3 — 확정 노선·정차 목록·정차명) · 커밋 뒤 탑승자 학생 id 1. 전체 8건(첫 도착 전 7건)에서
     * 줄었다. 캐시가 아니라 쿼리를 합친 것이라 무효화 지점이 없다 — 매 요청이 실시간 값을 읽는다.
     */
    @Test
    @DisplayName("L2 — 위치 1건의 SQL 은 5건이다(첫 도착 뒤 · 전 8건)")
    void 첫_도착_뒤_위치_1건의_SQL_은_5건이다() throws Exception {
        Seeded seeded = 회차를_심는다(true);

        assertThat(위치를_보내고_SQL_수를_센다(seeded)).isEqualTo(5);
    }

    @Test
    @DisplayName("L2 — 첫 도착 전에도 위치 1건의 SQL 은 5건이다(전 7건)")
    void 첫_도착_전_위치_1건의_SQL_은_5건이다() throws Exception {
        Seeded seeded = 회차를_심는다(false);

        assertThat(위치를_보내고_SQL_수를_센다(seeded)).isEqualTo(5);
    }

    /** 위치 방송 수신자 조회가 absent 를 빼고 학생 id 만 돌려준다(엔티티 목록 대신 투영 — L2). 거르는 일은 조회가 한다. */
    @Test
    @DisplayName("L2 — 위치 방송 수신자는 absent 를 뺀 학생 id 만이다")
    void 방송_수신자는_absent_를_뺀_학생_id_만이다() {
        Seeded seeded = 회차를_심는다(false);
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long stopId = fx.stop(seeded.academyId(), "37.570000", "126.980000");
        long waiting = fx.student(seeded.academyId(), "대기학생");
        long boarded = fx.student(seeded.academyId(), "탑승학생");
        long absent = fx.student(seeded.academyId(), "결석학생");
        fx.rider(seeded.runId(), waiting, stopId, RiderStatus.WAITING, seeded.now());
        fx.rider(seeded.runId(), boarded, stopId, RiderStatus.BOARDED, seeded.now());
        fx.rider(seeded.runId(), absent, stopId, RiderStatus.ABSENT, seeded.now());

        assertThat(runRiderRepository.findStudentIdsByRunIdAndStatusNot(seeded.runId(), RiderStatus.ABSENT))
                .containsExactlyInAnyOrder(waiting, boarded);
    }

    private record Seeded(long academyId, long runId, long driverAccountId, OffsetDateTime now) {
    }

    private Seeded 회차를_심는다(boolean firstStopArrived) {
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
        if (firstStopArrived) {
            jdbcTemplate.update("UPDATE run_stop SET arrived_at = now() WHERE id = ?", runStopId);
        }
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        return new Seeded(academyId, runId, driverAccountId, now);
    }

    private void 위치를_보낸다(Seeded seeded) throws Exception {
        mockMvc.perform(post("/api/v1/runs/" + seeded.runId() + "/position")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(seeded.driverAccountId(),
                                seeded.academyId(), Role.DRIVER, AccountStatus.ACTIVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat": 37.561000, "lng": 126.971000, "recorded_at": "%s"}
                                """.formatted(seeded.now().minusSeconds(2))))
                .andExpect(status().isNoContent());
    }

    /** Hibernate 가 준비한 문장 수의 요청 전후 차이 — 커밋 뒤 리스너(탑승자 조회)까지 같은 스레드라 포함된다. */
    private long 위치를_보내고_SQL_수를_센다(Seeded seeded) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        위치를_보낸다(seeded);
        return statistics.getPrepareStatementCount() - before;
    }

    /** 정차 목록(이름 포함) 조회문의 누적 실행 횟수 — 문장 원문의 투영 클래스 이름으로 찾는다. */
    private long 정차_목록_조회_횟수(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("PositionStopView"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }
}
