package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.run.event.RunEndedEvent;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.command.ChangeRequestStore;
import src.backend.request.controller.ChangeRequestFixtures;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * BR-110 — §9.7 알림 종류 중 적재 코드가 없던 3종: {@code run_ended}(회차 종료 → 관계자) ·
 * {@code assignment_changed}(배치 변경 → 해당 매니저, 커밋 후) · {@code absent}(관계자 통지가 없던 ① 변경 신청
 * 취소 · ② 취소 승인 → 관계자, 조율자 판정 2026-09-25).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@code assignment_changed} 는 커밋 후에만 적재되므로 실제 커밋·
 * 롤백 경계가 있어야 한다.
 */
@SpringBootTest
class RunEndedAssignmentAbsentNotificationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChangeRequestStore changeRequestStore;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private AccountRepository accountRepository;

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
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM change_request WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    private RunConfirmationFixtures fixtures() {
        return new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                stopRepository, studentRepository, weeklyAddressRepository, runRepository);
    }

    private ChangeRequestFixtures changeRequestFixtures() {
        return new ChangeRequestFixtures(accountRepository, guardianRepository, guardianStudentRepository,
                academyStaffRepository);
    }

    @Test
    void 회차가_끝나면_관계자에게_run_ended_가_적재된다() {
        long academyId = fixtures().academyWithCoordinates();
        long staffAccountId = changeRequestFixtures().staffOf(academyId);
        long runId = run(academyId);

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                new RunEndedEvent(runId, academyId, OffsetDateTime.now(), 0)));

        assertThat(count("run_ended", staffAccountId)).isEqualTo(1);
    }

    @Test
    void 배치가_바뀌면_커밋_후에만_해당_매니저에게_assignment_changed_가_적재된다() {
        long academyId = fixtures().academyWithCoordinates();
        long runId = run(academyId);
        Account account = accountRepository.save(Account.forSignup(academyId, "mgr" + System.nanoTime(), "x",
                "기사", "010-0000-0000", null, Role.DRIVER));
        Manager manager = managerRepository.save(Manager.register(academyId,
                new ManagerProfile("기사", "010-0000-0000", ManagerRole.DRIVER, null)));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        AssignmentChangedEvent event = new AssignmentChangedEvent(runId, academyId, manager.getId(),
                ManagerRole.DRIVER, OffsetDateTime.now());

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            eventPublisher.publishEvent(event);
            tx.setRollbackOnly();
        });
        assertThat(count("assignment_changed", account.getId())).as("롤백된 배치는 통지하지 않는다").isZero();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));
        // 커밋 뒤 적재는 알림 실행기에서 비동기로 돈다(R46 T-4) — 커밋한 호출이 돌아온 시점에는 아직 없을 수 있다
        await().atMost(Duration.ofSeconds(10)).until(() -> count("assignment_changed", account.getId()) == 1);
    }

    /**
     * AFTER_COMMIT 적재가 실패해도(예전에는 같은 {@code dedup_key} 의 중복이 예외였으나 지금은 건너뛴다 — Ruling 622) 이미 커밋된
     * 배치 변경의 호출자에게 예외가 퍼지면 안 된다(Ruling 379 후속, BR-207 과 같은 형태) — 퍼지면 배치는 저장됐는데
     * 요청이 500 으로 응답된다. 통지가 빠지는 것은 배치 자체를 되돌릴 이유가 아니다.
     */
    @Test
    void 통지_적재가_실패해도_커밋된_배치_변경의_호출자에게_예외가_퍼지지_않는다() {
        long academyId = fixtures().academyWithCoordinates();
        long runId = run(academyId);
        Account account = accountRepository.save(Account.forSignup(academyId, "mgr" + System.nanoTime(), "x",
                "기사", "010-0000-0000", null, Role.DRIVER));
        Manager manager = managerRepository.save(Manager.register(academyId,
                new ManagerProfile("기사", "010-0000-0000", ManagerRole.DRIVER, null)));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        AssignmentChangedEvent event = new AssignmentChangedEvent(runId, academyId, manager.getId(),
                ManagerRole.DRIVER, OffsetDateTime.now());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));

        assertThatCode(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> eventPublisher.publishEvent(event)))
                .as("같은 이벤트의 두 번째 커밋 — 적재가 중복으로 실패해도 호출자는 성공해야 한다")
                .doesNotThrowAnyException();
        // 두 번째 적재의 중복 실패도 실행기에서 삼켜지고 행은 한 건으로 유지된다(비동기라 잠시 지켜본다)
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(10))
                .until(() -> count("assignment_changed", account.getId()) == 1);
    }

    @Test
    void 즉시반영_구간_탑승_취소_신청은_관계자에게_absent_가_적재된다() {
        long academyId = fixtures().academyWithCoordinates();
        long staffAccountId = changeRequestFixtures().staffOf(academyId);
        long runId = run(academyId);
        long studentId = fixtures().student(academyId, "취소학생");
        long parentAccountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);
        Student student = studentRepository.findById(studentId).orElseThrow();
        Run run = runRepository.findById(runId).orElseThrow();

        changeRequestStore.submitCancel(student, run, parentAccountId, null);

        assertThat(count("absent", staffAccountId)).isEqualTo(1);
    }

    private long run(long academyId) {
        long busId = fixtures().bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");
        return fixtures().idleRun(academyId, busId, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    private int count(String type, long recipientAccountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = ? AND recipient_account_id = ?", Integer.class,
                type, recipientAccountId);
        return count == null ? 0 : count;
    }
}
