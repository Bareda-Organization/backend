package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.entity.RunRider;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;

/**
 * 비상 신고 접수가 저장하는 탑승자 수 — 명단 행을 엔티티로 읽지 않고 개수만 세어 저장한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(FixedClock20300401Config.class)
class EmergencyRaiseRiderCountTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    private static final int ROSTER_SIZE = 25;

    @Autowired
    private EmergencyCommandService emergencyCommandService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    private BoardingCommandFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = BoardingCommandFixtures.of(context, entityManager);
        AcademyRowsCleaner.delete(jdbcTemplate, ACADEMY_NAME);
    }

    @AfterEach
    void tearDown() {
        AcademyRowsCleaner.delete(jdbcTemplate, ACADEMY_NAME);
    }

    @Test
    @DisplayName("명단이 25명인 회차의 비상 신고는 탑승자 25명을 저장하되 탑승자 행을 읽어 오지 않는다")
    void 비상_신고는_탑승자_수만_세어_저장한다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        for (int i = 0; i < ROSTER_SIZE; i++) {
            fixtures.runRider(runId, fixtures.student(academyId, "학생" + i), stopId);
        }
        entityManager.clear();
        AuthUser escort = new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE);
        UUID clientKey = UUID.randomUUID();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        emergencyCommandService.raise(escort, runId,
                new EmergencyRaiseRequest("ACCIDENT", null, clientKey, null, null, null));

        assertThat(jdbcTemplate.queryForObject("SELECT rider_count FROM emergency_alert WHERE client_key = ?",
                Integer.class, clientKey)).isEqualTo(ROSTER_SIZE);
        assertThat(statistics.getEntityStatistics(RunRider.class.getName()).getLoadCount())
                .as("개수만 필요하므로 탑승자 엔티티를 읽지 않는다").isZero();
    }
}
