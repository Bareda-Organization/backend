package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.monitoring.controller.AdminMonitoringFixtures;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 메인 관리자 관제(§6.8) 호출 1회의 쿼리 수가 정차지 수에 비례하지 않고, 회차 하나가 더하는 쿼리가 확정 노선·정차
 * 순서 두 건뿐이다(BR-065). 정차마다 정차지를 따로 읽고 같은 회차의 확정 노선·정차를 두 번 읽으면 오늘 회차 전부를
 * 싣는 이 화면이 한 번에 수백 쿼리가 된다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class AdminAcademyLiveQueryCountTest {

    private static final String LIVE = "/api/v1/admin/academies/%d/runs/live";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

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

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;


    @Test
    void 정차지가_늘어도_관제_쿼리_수는_같다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        String token = 메인관리자_토큰(f.systemAdminAccount("메인관리자"));
        long versionId = 운행_중_회차(f, academyId);
        f.runStopForStop(versionId, f.stop(academyId, "37.500000", "127.030000"), 1, now().plusMinutes(5));

        long oneStop = 관제_쿼리_수(academyId, token);
        for (int seq = 2; seq <= 4; seq++) {
            f.runStopForStop(versionId, f.stop(academyId, "37.50" + seq + "000", "127.030000"), seq,
                    now().plusMinutes(5 + seq));
        }

        assertThat(관제_쿼리_수(academyId, token)).isEqualTo(oneStop);
    }

    /** BR-247 — 확정 노선·정차 순서를 회차 수와 무관한 배치 조회로 읽는다(회차마다 2쿼리를 더하던 것을 없앤다). */
    @Test
    void 회차가_늘어도_관제_쿼리_수는_같다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        String token = 메인관리자_토큰(f.systemAdminAccount("메인관리자"));
        f.runStopForStop(운행_중_회차(f, academyId), f.stop(academyId, "37.500000", "127.030000"), 1,
                now().plusMinutes(5));

        long oneRun = 관제_쿼리_수(academyId, token);
        for (int i = 0; i < 3; i++) {
            f.runStopForStop(운행_중_회차(f, academyId), f.stop(academyId, "37.51" + i + "000", "127.030000"), 1,
                    now().plusMinutes(5));
        }

        assertThat(관제_쿼리_수(academyId, token)).isEqualTo(oneRun);
    }

    /** BR-247 — 관계자 실시간 조회는 현재·다음 정차명을 회차마다 따로 읽던 것을 한 번에 읽는다. */
    @Test
    void 관계자_실시간_조회도_회차가_늘어도_쿼리_수가_같다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        String token = "Bearer " + tokenProvider.createAccessToken(f.staffAccount(academyId, "관계자"), academyId,
                Role.STAFF, AccountStatus.ACTIVE);
        f.runStopForStop(운행_중_회차(f, academyId), f.stop(academyId, "37.500000", "127.030000"), 1,
                now().plusMinutes(5));

        long oneRun = 쿼리_수("/api/v1/staff/runs/live", token);
        for (int i = 0; i < 3; i++) {
            f.runStopForStop(운행_중_회차(f, academyId), f.stop(academyId, "37.51" + i + "000", "127.030000"), 1,
                    now().plusMinutes(5));
        }

        assertThat(쿼리_수("/api/v1/staff/runs/live", token)).isEqualTo(oneRun);
    }

    private long 운행_중_회차(AdminMonitoringFixtures f, long academyId) {
        OffsetDateTime departTime = now().minusMinutes(20);
        long runId = f.movingRun(academyId, f.bus(academyId), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30), now().minusMinutes(15), 40);
        return f.confirmedRouteWithVersion(runId, now().minusMinutes(25));
    }

    private long 관제_쿼리_수(long academyId, String token) throws Exception {
        return 쿼리_수(LIVE.formatted(academyId), token);
    }

    private long 쿼리_수(String path, String token) throws Exception {
        // 시험 트랜잭션의 1차 캐시가 방금 만든 정차지를 들고 있으면 정차마다의 조회가 DB 에 닿지 않아 가려진다
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String 메인관리자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private AdminMonitoringFixtures fixtures() {
        return new AdminMonitoringFixtures(academyRepository, academyStaffRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                managerRepository, assignmentRepository);
    }
}
