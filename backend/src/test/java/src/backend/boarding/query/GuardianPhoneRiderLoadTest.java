package src.backend.boarding.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

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
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;

/**
 * 보호자 전화 단건 조회가 읽는 탑승자 행 수 — 명단 전체가 아니라 요청한 탑승자 1명만 읽는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(FixedClock20300401Config.class)
class GuardianPhoneRiderLoadTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    private static final int ROSTER_SIZE = 25;

    @Autowired
    private GuardianPhoneQueryService guardianPhoneQueryService;

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
    @DisplayName("명단이 25명이어도 보호자 전화 조회는 요청한 탑승자 1명만 읽고, 결석 처리된 탑승자는 404 다")
    void 보호자_전화_조회는_탑승자_한_명만_읽는다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long target = 0;
        long absent = 0;
        for (int i = 0; i < ROSTER_SIZE; i++) {
            long riderId = fixtures.runRider(runId, fixtures.student(academyId, "학생" + i), stopId);
            target = i == 0 ? riderId : target;
            absent = i == 1 ? riderId : absent;
        }
        fixtures.markAbsent(absent);
        entityManager.clear();
        AuthUser escort = new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        guardianPhoneQueryService.guardianPhone(escort, runId, target);

        assertThat(statistics.getEntityStatistics(RunRider.class.getName()).getLoadCount())
                .as("명단 25명 중 요청한 탑승자만 읽어야 한다").isEqualTo(1);
        long absentRider = absent;
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> guardianPhoneQueryService.guardianPhone(escort, runId, absentRider)).getErrorCode())
                .isEqualTo(ErrorCode.RIDER_NOT_FOUND);
    }
}
