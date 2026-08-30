package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 시 {@code route_changed} 알림 발행(Phase 7 목표 7) — {@link RunRouteConfirmedNotificationListener}
 * 가 {@code RunConfirmationPersistence.persist} 와 같은 트랜잭션에서 적재하는지를 실제 커밋·롤백
 * 경계로 검증한다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다({@code RunConfirmationConcurrencyTest} 와 같은 근거) —
 * 롤백 시 미발행(목표 7의 급소)을 검사하려면 실제 트랜잭션 경계가 있어야 하는데, 클래스에 붙이면
 * {@link TransactionTemplate} 이 테스트 트랜잭션에 합류해 {@code setRollbackOnly} 가 테스트 전체를
 * 되돌릴 뿐 커밋 여부를 가려낼 수단이 없어진다.
 */
@SpringBootTest
class RunRouteConfirmedNotificationTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @AfterEach
    void 뒷정리한다() {
        // 삭제 순서는 FK 제약이 강제한다(V1__init_schema.sql) — run 삭제가 confirmed_route·route_version·
        // run_stop·run_rider·assignment 를 CASCADE 로 함께 지우고, route 삭제가 route_stop 을, student
        // 삭제가 weekly_address 를 같이 지운다. route→bus, stop→academy, account→academy 는 RESTRICT라
        // 참조하는 쪽을 먼저 지워야 한다.
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    private RunConfirmationFixtures fixtures() {
        return new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                stopRepository, studentRepository, weeklyAddressRepository, runRepository);
    }

    /** 계정이 연결된 매니저 1명을 등록하고 그 회차에 배치한다. */
    private void 배치된_매니저를_만든다(long academyId, long runId, ManagerRole role, String name) {
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, "010-0000-0000", role, null)));
        Account account = accountRepository.save(Account.forSignup(academyId, name + academyId, "x", name,
                "010-0000-0000", null, role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        assignmentRepository.save(Assignment.uponAssignment(runId, manager.getId(), role, OffsetDateTime.now(clock),
                null));
    }

    /** 계정이 연결되지 않은 매니저(가입 승인 전)를 그 회차에 배치한다 — {@code accountId} 가 {@code NULL} 이다. */
    private void 계정_없는_매니저를_만든다(long academyId, long runId, ManagerRole role, String name) {
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, "010-0000-0000", role, null)));
        assignmentRepository.save(Assignment.uponAssignment(runId, manager.getId(), role, OffsetDateTime.now(clock),
                null));
    }

    /**
     * 계정이 연결된 매니저를 그 회차에 배치한 뒤 <b>엔티티를 직접 조작해</b> 삭제한다(Phase 8 목표 17).
     *
     * <p>{@code ManagerCommandService.delete} 를 거치지 않는다 — MGR-04 가 배치된 매니저의 삭제를
     * 원천 차단해 정상 흐름으로는 "배치는 있는데 매니저는 삭제됨" 이라는 상태 자체를 만들 수 없다.
     * {@code AssignmentRepository#findAssignedManagerAccounts} 의 {@code deletedAt IS NULL} 필터가
     * 실제로 이 행을 거르는지는 이렇게 가드를 우회해 상태를 직접 만들어야만 검증할 수 있다.
     */
    private void 삭제된_매니저를_만든다(long academyId, long runId, ManagerRole role, String name) {
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, "010-0000-0000", role, null)));
        Account account = accountRepository.save(Account.forSignup(academyId, name + academyId, "x", name,
                "010-0000-0000", null, role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        assignmentRepository.save(Assignment.uponAssignment(runId, manager.getId(), role, OffsetDateTime.now(clock),
                null));
        manager.delete(OffsetDateTime.now(clock));
        managerRepository.save(manager);
    }

    private long 확정_대상_회차를_만든다(RunConfirmationFixtures fixtures, long academyId, long busId) {
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.561000", "126.971000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        long student = fixtures.student(academyId, "학생1");
        fixtures.verifiedAddress(student, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        return fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    private long 알림_행수(long runId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'route_changed' AND dedup_key LIKE ?",
                Integer.class, "route_changed:" + runId + ":%");
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("목표7 — 확정하면 배치된 기사·동승자 앞으로 route_changed 알림이 적재된다")
    void 확정하면_배치된_기사_동승자에게_알림이_적재된다() {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long runId = 확정_대상_회차를_만든다(fixtures, academyId, busId);
        배치된_매니저를_만든다(academyId, runId, ManagerRole.DRIVER, "기사");
        배치된_매니저를_만든다(academyId, runId, ManagerRole.ESCORT, "동승자");

        confirmationService.confirmOne(runId);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(알림_행수(runId)).as("기사·동승자 2명 각각 앞으로 1건씩 적재돼야 한다").isEqualTo(2);

        String recipientRole = jdbcTemplate.queryForObject(
                "SELECT recipient_role FROM notification_log WHERE type = 'route_changed' "
                        + "AND dedup_key LIKE ? AND recipient_name = '기사'",
                String.class, "route_changed:" + runId + ":%");
        assertThat(recipientRole).isEqualTo("driver");
    }

    @Test
    @DisplayName("목표7 급소 — 확정 트랜잭션이 롤백되면 알림도 적재되지 않는다")
    void 확정_트랜잭션이_롤백되면_알림도_적재되지_않는다() {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long runId = 확정_대상_회차를_만든다(fixtures, academyId, busId);
        배치된_매니저를_만든다(academyId, runId, ManagerRole.DRIVER, "기사");

        new TransactionTemplate(transactionManager).execute(status -> {
            confirmationService.confirmOne(runId);
            status.setRollbackOnly();
            return null;
        });

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("확정 표시까지 롤백돼야 한다").isEqualTo(RunStatus.IDLE);
        assertThat(알림_행수(runId)).as("커밋되지 않은 확정의 알림은 남아서는 안 된다").isZero();
    }

    @Test
    @DisplayName("목표7 판단3 — 계정이 연결되지 않은 매니저는 알림을 받지 않는다")
    void 계정이_연결되지_않은_매니저는_알림을_받지_않는다() {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long runId = 확정_대상_회차를_만든다(fixtures, academyId, busId);
        계정_없는_매니저를_만든다(academyId, runId, ManagerRole.DRIVER, "미승인기사");
        배치된_매니저를_만든다(academyId, runId, ManagerRole.ESCORT, "동승자");

        confirmationService.confirmOne(runId);

        assertThat(알림_행수(runId)).as("계정 연결된 동승자 1명만 알림을 받아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("목표17 — 배치 뒤 곧바로 삭제된 매니저(MGR-04 가 정상 흐름에서는 막는 상태)는 알림을 받지 않는다")
    void 삭제된_매니저는_알림을_받지_않는다() {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long runId = 확정_대상_회차를_만든다(fixtures, academyId, busId);
        삭제된_매니저를_만든다(academyId, runId, ManagerRole.DRIVER, "삭제된기사");
        배치된_매니저를_만든다(academyId, runId, ManagerRole.ESCORT, "동승자");

        confirmationService.confirmOne(runId);

        assertThat(알림_행수(runId))
                .as("findAssignedManagerAccounts 의 deletedAt IS NULL 필터가 삭제된 기사를 걸러 동승자 1명만 남아야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("목표7 판단4 — 배정이 없는 회차는 확정은 성공하고 알림도 없다")
    void 배정이_없는_회차는_확정은_성공하고_알림도_없다() {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long runId = 확정_대상_회차를_만든다(fixtures, academyId, busId);

        confirmationService.confirmOne(runId);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("배정 없음은 오류가 아니다 — 확정은 그대로 성공해야 한다").isEqualTo(RunStatus.CONFIRMED);
        assertThat(알림_행수(runId)).isZero();
    }
}
