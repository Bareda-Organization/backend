package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.repository.GuardianRepository;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunDraft;
import src.backend.run.repository.RunRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * BR-204·BR-240 — 취소·스케줄 반영은 회차 행을 잠근 <b>뒤 다시 읽은 상태</b>로 판정한다. 낡게 읽은 엔티티로 취소하거나
 * 계획을 옮기면 변경 감지가 행 전체를 UPDATE 해, 그 사이 커밋된 확정·시작 전이를 되돌린다(Hibernate 기본값은 변경된
 * 컬럼만이 아니라 전 컬럼을 쓴다). 이미 취소된 회차의 재취소는 출처·시각을 바꾸지 않는다(Ruling 376).
 *
 * <p>경쟁을 결정적으로 만들려고 한 트랜잭션 안에서 <b>먼저 읽고</b> → JDBC(별도 커넥션)로 전이를 커밋하고 → 그 낡은
 * 엔티티로 실행한다. {@code @Transactional} 을 붙이면 전이가 같은 트랜잭션에 묶여 경쟁이 사라지므로 커밋을 남기고 직접 지운다.
 */
@SpringBootTest
class RunCancellationLockTest {

    private final AuthUser staff = new AuthUser(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

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
    @Autowired private RunCancellation runCancellation;
    @Autowired private RunCommandService runCommandService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> academyIds = new ArrayList<>();

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("BR-204 — 낡게 읽은 confirmed 회차를 그 사이 기사가 시작했으면 관계자 취소는 409 이고 moving 이 그대로다")
    void 그_사이_시작된_회차는_취소가_409_이고_상태가_그대로다() {
        Setup s = 회차를_만든다(true);

        assertThatThrownBy(() -> 낡게_읽고(s, () -> jdbcTemplate.update(
                "UPDATE run SET status = 'moving', started_at = now() WHERE id = ?", s.runId),
                run -> runCancellation.cancel(staff(s), run, RunCancelSource.STAFF)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RUN_ALREADY_STARTED));

        Map<String, Object> row = 행(s.runId);
        assertThat(row.get("status")).as("취소가 시작 전이를 되돌리면 달리는 버스가 confirmed 로 돌아간다").isEqualTo("moving");
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("canceled_at")).isNull();
    }

    @Test
    @DisplayName("BR-204 — 스케줄이 낡게 읽은 idle 회차를 그 사이 확정 배치가 확정했으면 스케줄 취소는 건너뛰고 confirmed 가 그대로다")
    void 그_사이_확정된_회차는_스케줄_취소가_건너뛴다() {
        Setup s = 회차를_만든다(false);

        낡게_읽고(s, () -> jdbcTemplate.update(
                "UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE id = ?", s.runId),
                run -> runCancellation.cancel(staff(s), run, RunCancelSource.SCHEDULE));

        Map<String, Object> row = 행(s.runId);
        assertThat(row.get("status")).as("스케줄 반영은 확정된 회차를 idle 로 되돌리지 않는다").isEqualTo("confirmed");
        assertThat(row.get("confirmed_at")).isNotNull();
        assertThat(row.get("canceled_at")).as("이미 확정된 회차는 스케줄이 취소하지 않는다(§5.10)").isNull();
    }

    @Test
    @DisplayName("BR-204 — 스케줄 수정이 낡게 읽은 idle 회차를 그 사이 확정 배치가 확정했으면 계획을 옮기지 않고 confirmed 가 그대로다")
    void 그_사이_확정된_회차에는_계획_이동이_건너뛴다() {
        Setup s = 회차를_만든다(false);
        Object departBefore = 행(s.runId).get("depart_time");
        RunDraft plan = new RunDraft(s.academyId, s.busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                LocalTime.of(23, 40), "옮긴출발지", "옮긴도착지", null);

        낡게_읽고(s, () -> jdbcTemplate.update(
                "UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE id = ?", s.runId),
                run -> runCommandService.moveToPlan(run, plan));

        Map<String, Object> row = 행(s.runId);
        assertThat(row.get("status")).isEqualTo("confirmed");
        assertThat(row.get("confirmed_at")).as("확정 시각이 NULL 로 되돌면 route_confirmed 알림만 남고 회차는 idle 이다").isNotNull();
        assertThat(row.get("depart_time")).as("확정된 회차의 출발 시각은 스케줄 수정이 옮기지 않는다").isEqualTo(departBefore);
    }

    @Test
    @DisplayName("BR-240 — 스케줄이 취소한 회차를 관계자가 다시 취소해도 출처·취소 시각이 그대로다(Ruling 376)")
    void 이미_취소된_회차의_재취소는_출처와_시각을_바꾸지_않는다() {
        Setup s = 회차를_만든다(false);
        jdbcTemplate.update("UPDATE run SET canceled_at = now() - interval '1 hour', cancel_source = 'schedule' "
                + "WHERE id = ?", s.runId);
        Map<String, Object> before = 행(s.runId);

        runCommandService.cancel(staff(s), s.runId);

        Map<String, Object> after = 행(s.runId);
        assertThat(after.get("cancel_source")).as("staff 로 바뀌면 스케줄을 다시 켜도 되살아나지 않는다").isEqualTo("schedule");
        assertThat(after.get("canceled_at")).isEqualTo(before.get("canceled_at"));
    }

    @Test
    @DisplayName("BR-346 — 스케줄이 취소한 회차를 읽은 뒤 그 사이 취소가 풀렸으면 되살림은 계획을 다시 옮기지 않는다")
    void 그_사이_되살아난_회차에는_되살림이_계획을_옮기지_않는다() {
        Setup s = 스케줄이_취소한_회차를_만든다();
        Object departBefore = 행(s.runId).get("depart_time");

        낡게_읽고(s, () -> jdbcTemplate.update("UPDATE run SET canceled_at = NULL, cancel_source = NULL WHERE id = ?",
                s.runId), run -> runCommandService.reinstateToPlan(run, 옮길_계획(s)));

        Map<String, Object> row = 행(s.runId);
        assertThat(row.get("depart_time")).as("두 번째 되살림이 계획을 덮어쓰면 안 된다 — 잠그고 다시 읽은 출처로 판정한다")
                .isEqualTo(departBefore);
        assertThat(row.get("canceled_at")).isNull();
    }

    @Test
    @DisplayName("BR-346 — 스케줄이 취소한 회차를 읽은 뒤 그 사이 idle 이 아니게 됐으면 되살림은 건드리지 않는다")
    void 그_사이_시작_전이_아니게_된_회차는_되살림이_건너뛴다() {
        Setup s = 스케줄이_취소한_회차를_만든다();
        Object departBefore = 행(s.runId).get("depart_time");

        낡게_읽고(s, () -> jdbcTemplate.update("UPDATE run SET status = 'confirmed' WHERE id = ?", s.runId),
                run -> runCommandService.reinstateToPlan(run, 옮길_계획(s)));

        Map<String, Object> row = 행(s.runId);
        assertThat(row.get("canceled_at")).as("시작 전이 아닌 회차의 취소를 되살림이 풀면 안 된다").isNotNull();
        assertThat(row.get("depart_time")).isEqualTo(departBefore);
    }

    private Setup 스케줄이_취소한_회차를_만든다() {
        Setup s = 회차를_만든다(false);
        jdbcTemplate.update("UPDATE run SET canceled_at = now(), cancel_source = 'schedule' WHERE id = ?", s.runId);
        return s;
    }

    private RunDraft 옮길_계획(Setup s) {
        return new RunDraft(s.academyId, s.busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                LocalTime.of(23, 40), "옮긴출발지", "옮긴도착지", null);
    }

    private void 낡게_읽고(Setup s, Runnable competing, Consumer<Run> action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Run run = runRepository.findByIdAndAcademyId(s.runId, s.academyId).orElseThrow();
            TransactionTemplate 별도 = new TransactionTemplate(transactionManager);
            별도.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            별도.executeWithoutResult(other -> competing.run()); // 다른 커넥션에서 커밋 — 같은 트랜잭션이면 경쟁이 아니다
            action.accept(run);
        });
    }

    private Setup 회차를_만든다(boolean confirmed) {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        OffsetDateTime depart = OffsetDateTime.now().plusDays(1);
        long runId = confirmed ? fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, depart, OffsetDateTime.now())
                : fx.idleRun(academyId, busId, Direction.TO_ACADEMY, depart);
        return new Setup(academyId, busId, runId);
    }

    private AuthUser staff(Setup s) {
        return new AuthUser(1L, s.academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private Map<String, Object> 행(long runId) {
        return jdbcTemplate.queryForMap("SELECT status, started_at, confirmed_at, depart_time, canceled_at, "
                + "cancel_source FROM run WHERE id = ?", runId);
    }

    private record Setup(long academyId, long busId, long runId) {
    }
}
