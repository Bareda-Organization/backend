package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.command.RunRiderPersistence;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.AuthUser;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.location.dto.RunPositionRequest;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.event.StopArrivedEvent;
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
@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "app.security.account-standing-cache-ttl=5s"})
@AutoConfigureMockMvc
class RunPositionQueryCountTest {

    @Autowired private ApplicationContext applicationContext;
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
    @Autowired private RunPositionCommandService positionCommandService;
    @Autowired private RunRiderPersistence runRiderPersistence;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @MockitoSpyBean private WebSocketBroadcastGateway gateway;

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
     * Ruling 671 — <b>캐시가 비어 있는</b> 첫 위치 1건의 SQL 은 6건이다: 인증 필터의 계정 상태 확인 1(Ruling 880) · 인가 exists 1 ·
     * 회차 1 · INSERT 1 · 정차 목록 1 · 커밋 뒤 탑승자 학생 id 1. 두 목록은 이벤트가 커밋될 때만 다시 읽으므로 이어지는 위치는
     * 아래 RT-5 시험이 3건을 센다.
     */
    @Test
    @DisplayName("L2 — 캐시가 빈 첫 위치 1건의 SQL 은 6건이다(첫 도착 뒤 · 880 포함)")
    void 첫_도착_뒤_위치_1건의_SQL_은_6건이다() throws Exception {
        Seeded seeded = 회차를_심는다(true);

        assertThat(위치를_보내고_SQL_수를_센다(seeded)).isEqualTo(6);
    }

    @Test
    @DisplayName("L2 — 캐시가 빈 첫 위치는 첫 도착 전에도 SQL 6건이다(880 포함)")
    void 첫_도착_전_위치_1건의_SQL_은_6건이다() throws Exception {
        Seeded seeded = 회차를_심는다(false);

        assertThat(위치를_보내고_SQL_수를_센다(seeded)).isEqualTo(6);
    }

    /**
     * RT-5(Ruling 899 K) — 정차 목록 · 탑승 학생 목록은 도착 · 결석 · 전환 이벤트가 커밋될 때만 다시 읽는다. 인증 필터의 계정 상태 확인(Ruling 880 ·
     * BE-RT 가 캐시한다)은 이 경로의 몫이 아니라 서비스를 직접 불러 센다. 데워진 위치 1건은 인가 exists 1 + 회차 1 + INSERT 1 = 3건이다.
     */
    @Test
    @DisplayName("RT-5 — 데워진 뒤 위치 1건의 SQL 은 3건 이하다")
    void 데워진_뒤_위치_1건의_SQL_은_3건_이하다() {
        Seeded seeded = 회차를_심는다(true);
        직접_보낸다(seeded);

        long warm = 직접_보내고_SQL_수를_센다(seeded);

        assertThat(warm).isLessThanOrEqualTo(3);
    }

    /**
     * 실제 요청 경로(인증 필터 · 컨트롤러 · 서비스 · 커밋 뒤 리스너) 전체의 SQL — 시험 JVM 은 계정 상태 캐시를 0초로 꺼 두므로(다른 시험이 계정 상태를
     * 바로 바꾼다) 이 시험만 5초로 되돌려, 운영과 같이 인증 필터의 계정 상태 확인이 캐시에서 나오는 상태를 센다. 첫 요청이 캐시를 채운 뒤의 3건이 각각 3건 이하다.
     */
    @Test
    @DisplayName("RT-5 — 실제 요청 경로로 데워진 위치 3건의 SQL 은 각각 3건 이하다(계정 상태 캐시 5초)")
    void 데워진_실제_요청_3건의_SQL_은_각각_3건_이하다() throws Exception {
        Seeded seeded = 회차를_심는다(true);
        위치를_보낸다(seeded, 0);

        for (int i = 1; i <= 3; i++) {
            assertThat(위치를_보내고_SQL_수를_센다(seeded, i)).as("데워진 %d번째 요청", i).isLessThanOrEqualTo(3);
        }
    }

    @Test
    @DisplayName("RT-5 — 결석 전이가 커밋되면 다음 위치 한 번만 학생 목록을 다시 읽고, 결석 학생 채널로는 방송하지 않는다")
    void 결석_뒤_첫_위치에서만_학생_목록을_다시_읽는다() {
        Seeded seeded = 회차를_심는다(false);
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long stopId = fx.stop(seeded.academyId(), "37.570000", "126.980000");
        long staying = fx.student(seeded.academyId(), "남는학생");
        long leaving = fx.student(seeded.academyId(), "결석할학생");
        fx.rider(seeded.runId(), staying, stopId, RiderStatus.WAITING, seeded.now());
        long leavingRiderId = fx.rider(seeded.runId(), leaving, stopId, RiderStatus.WAITING, seeded.now());
        직접_보낸다(seeded);
        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("데워진 위치").isEqualTo(3);

        transactionTemplate.executeWithoutResult(status -> runRiderPersistence
                .markAbsentBySystem(runRiderRepository.findById(leavingRiderId).orElseThrow(), seeded.now()));
        org.mockito.Mockito.clearInvocations(gateway);

        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("결석 뒤 첫 위치 — 학생 목록 1건 더").isEqualTo(4);
        // 위치 1건 = 방송 1건 — 그 방송의 목적지 전부에서 결석한 학생 채널만 빠져 있어야 한다
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<src.backend.global.websocket.BroadcastDelivery>> deliveries =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(gateway).broadcast(org.mockito.ArgumentMatchers.eq("position"),
                org.mockito.ArgumentMatchers.eq(seeded.runId()), any(), deliveries.capture());
        assertThat(deliveries.getValue()).flatExtracting(src.backend.global.websocket.BroadcastDelivery::destinations)
                .doesNotContain(WebSocketDestinations.studentRun(leaving))
                .contains(WebSocketDestinations.studentRun(staying));
        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("그 다음 위치는 다시 3건").isEqualTo(3);
    }

    @Test
    @DisplayName("RT-5 — 정류장 도착이 커밋되면 다음 위치 한 번만 정차 목록을 다시 읽는다")
    void 도착_뒤_첫_위치에서만_정차_목록을_다시_읽는다() {
        Seeded seeded = 회차를_심는다(false);
        직접_보낸다(seeded);
        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("데워진 위치").isEqualTo(3);

        eventPublisher.publishEvent(new StopArrivedEvent(seeded.runId(), seeded.academyId(), 1L, 1, "정류장",
                seeded.now(), null));

        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("도착 뒤 첫 위치 — 정차 목록 1건 더").isEqualTo(4);
        assertThat(직접_보내고_SQL_수를_센다(seeded)).as("그 다음 위치는 다시 3건").isEqualTo(3);
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
        위치를_보낸다(seeded, 0);
    }

    /** {@code sequence} 번째 요청 — 기록 시각을 1초씩 앞으로 보내 앞 위치보다 오래된 값으로 걸러지지 않게 한다. */
    private void 위치를_보낸다(Seeded seeded, int sequence) throws Exception {
        mockMvc.perform(post("/api/v1/runs/" + seeded.runId() + "/position")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(seeded.driverAccountId(),
                                seeded.academyId(), Role.DRIVER, AccountStatus.ACTIVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat": 37.561000, "lng": 126.971000, "recorded_at": "%s"}
                                """.formatted(seeded.now().minusSeconds(2).plusSeconds(sequence))))
                .andExpect(status().isNoContent());
    }


    /** 인증 필터를 거치지 않고 서비스를 직접 부른다 — 위치 경로의 SQL(인가 · 회차 · INSERT · 목록)만 센다. */
    private void 직접_보낸다(Seeded seeded) {
        AuthUser driver = new AuthUser(seeded.driverAccountId(), seeded.academyId(), Role.DRIVER, AccountStatus.ACTIVE, false);
        positionCommandService.receive(driver, seeded.runId(), new RunPositionRequest(new java.math.BigDecimal("37.561000"),
                new java.math.BigDecimal("126.971000"), seeded.now().minusSeconds(2), null, null));
    }

    private long 직접_보내고_SQL_수를_센다(Seeded seeded) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        직접_보낸다(seeded);
        return statistics.getPrepareStatementCount() - before;
    }

    /** Hibernate 가 준비한 문장 수의 요청 전후 차이 — 커밋 뒤 리스너(탑승자 조회)까지 같은 스레드라 포함된다. */
    private long 위치를_보내고_SQL_수를_센다(Seeded seeded) throws Exception {
        return 위치를_보내고_SQL_수를_센다(seeded, 0);
    }

    private long 위치를_보내고_SQL_수를_센다(Seeded seeded, int sequence) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        위치를_보낸다(seeded, sequence);
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
