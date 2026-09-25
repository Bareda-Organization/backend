package src.backend.exception.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;

/**
 * 비상 목록 2종(§5.16 · §6.11)의 쿼리 수가 신고 수에 비례하지 않는다(BR-087) — 신고마다 배치 인력을 따로 읽으면
 * 무기한 보존되는 {@code emergency_alert} 이 쌓일수록 호출 한 번이 수백~수천 쿼리가 된다.
 *
 * <p>Hibernate 통계로 목록 호출 1회의 준비문 수를 센다 — 신고 1건일 때와 4건일 때가 같아야 한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class EmergencyListQueryCountTest {

    private static final int EXTRA_ALERTS = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Test
    void 관계자_목록의_쿼리_수가_신고_수에_비례하지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        String staffToken = 토큰(fixtures.staffAccount(academyId, "직원"), academyId, Role.STAFF);
        신고를_발신한다(fixtures, academyId);

        long oneAlert = 목록_쿼리_수("/api/v1/staff/emergencies", staffToken);
        for (int i = 0; i < EXTRA_ALERTS; i++) {
            신고를_발신한다(fixtures, academyId);
        }

        assertThat(목록_쿼리_수("/api/v1/staff/emergencies", staffToken)).isEqualTo(oneAlert);
    }

    @Test
    void 메인_관리자_목록의_쿼리_수가_신고_수에_비례하지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        String adminToken = "Bearer " + tokenProvider.createAccessToken(fixtures.systemAdminAccount("메인관리자"), null,
                Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
        신고를_발신한다(fixtures, academyId);
        String list = "/api/v1/admin/emergencies?academy_id=" + academyId;

        long oneAlert = 목록_쿼리_수(list, adminToken);
        for (int i = 0; i < EXTRA_ALERTS; i++) {
            신고를_발신한다(fixtures, academyId);
        }

        assertThat(목록_쿼리_수(list, adminToken)).isEqualTo(oneAlert);
    }

    private long 목록_쿼리_수(String path, String token) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private void 신고를_발신한다(EmergencyFixtures fixtures, long academyId) throws Exception {
        long runId = fixtures.confirmedRun(academyId, fixtures.bus(academyId), OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        mockMvc.perform(post("/api/v1/runs/%d/emergency".formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"accident\",\"client_key\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private EmergencyFixtures fixtures() {
        return new EmergencyFixtures(academyRepository, busRepository, accountRepository, managerRepository,
                assignmentRepository, runRepository, academyStaffRepository);
    }
}
