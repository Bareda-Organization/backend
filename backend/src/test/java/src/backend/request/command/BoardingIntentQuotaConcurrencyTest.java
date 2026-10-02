package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.notification.command.IntentNotificationListener;
import src.backend.request.dto.BoardingIntentToggleRequest;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * BR-027 — ②구간 "회차당 1회" 한도(C-04)를 부·모 두 보호자가 거의 동시에 소비하면 두 트랜잭션이 모두
 * {@code change_used_count=0} 을 읽고 둘 다 통과해 승인 대기 2건이 생긴다. 두 요청이 한도를 읽은 직후
 * 서로를 기다리게 해 그 창을 결정적으로 만든다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 각자 커밋해야 경쟁이 성립한다.
 */
@SpringBootTest
class BoardingIntentQuotaConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private BoardingIntentCommandService boardingIntentCommandService;

    @Autowired
    private ChangeRequestStore changeRequestStore;

    @Autowired
    private BoardingIntentRepository boardingIntentRepository;

    @MockitoSpyBean
    private IntentNotificationListener intentNotificationListener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

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

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM change_request WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM boarding_intent WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)",
                    academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN "
                    + "(SELECT id FROM student WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM route WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM guardian WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
    }

    @Test
    void 두_보호자가_동시에_승인대기구간_한도를_쓰면_한_건만_접수된다() throws Exception {
        BoardingIntentFixtures fixtures = new BoardingIntentFixtures(academyRepository, busRepository,
                studentRepository, guardianRepository, guardianStudentRepository, accountRepository,
                academyStaffRepository, runRepository, stopRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, managerRepository,
                assignmentRepository, routeRepository, routeStopRepository, weeklyAddressRepository);
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "동시학생");
        BoardingIntentFixtures.GuardianAccount mother = fixtures.guardian(academyId, "엄마");
        BoardingIntentFixtures.GuardianAccount father = fixtures.guardian(academyId, "아빠");
        fixtures.linkChild(mother.guardianId(), studentId, now.minusDays(1));
        fixtures.linkChild(father.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures.run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));
        long stopId = fixtures.stop(academyId, "37.563000", "126.973000");
        fixtures.enrol(academyId, busId, studentId, stopId);
        fixtures.confirmedSingleRiderStop(runId, studentId, stopId, now.minusMinutes(10));
        boardingIntentRepository.save(BoardingIntent.forRun(runId, studentId, now));

        // 두 요청 모두 한도를 판정한 뒤(승인 요청 알림 직전) 서로를 기다린다 — 옛 코드는 둘 다
        // change_used_count=0 을 본 채 여기 도착한다. 조건부 UPDATE 면 뒤 요청은 행 잠금에서 멈춰 도착하지
        // 못하므로, 앞 요청은 "상대 도착 또는 상대의 잠금 대기" 중 먼저 오는 것을 보고 진행한다.
        AtomicInteger 도착 = new AtomicInteger();
        doAnswer(invocation -> {
            ApprovalRequestedEvent event = invocation.getArgument(0);
            if (event.runId() == runId) {
                도착.incrementAndGet();
                상대가_도착하거나_잠금_대기할_때까지_기다린다(도착);
            }
            return invocation.callRealMethod();
        }).when(intentNotificationListener).appendApprovalRequested(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (BoardingIntentFixtures.GuardianAccount guardian : List.of(mother, father)) {
                AuthUser requester = new AuthUser(guardian.accountId(), academyId, Role.PARENT, AccountStatus.ACTIVE);
                results.add(pool.submit(() -> toggleOff(requester, studentId, runId)));
            }
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> result : results) {
                outcomes.add(result.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS));
            }

            assertThat(outcomes).as("한 건만 접수되고 다른 한 건은 한도 소진이어야 한다")
                    .containsExactlyInAnyOrder(null, ErrorCode.CHANGE_LIMIT_REACHED);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ?", Integer.class, runId,
                    studentId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 한도 행이 아직 없을 때 두 요청이 동시에 처음 만들면 두 번째가 {@code uk_boarding_intent_run_student}
     * 위반으로 500 이 되던 경로 — 앞선 트랜잭션이 행을 넣고 커밋을 미루는 동안 뒤 요청이 그 행을 기다리게
     * 한 뒤 커밋한다. 뒤 요청은 업무 규칙대로 처리돼야 한다(여기서는 앞선 행이 한도를 쓰지 않았으니 접수).
     */
    @Test
    void 한도_행을_동시에_처음_만들어도_제약_위반으로_실패하지_않는다() throws Exception {
        BoardingIntentFixtures fixtures = new BoardingIntentFixtures(academyRepository, busRepository,
                studentRepository, guardianRepository, guardianStudentRepository, accountRepository,
                academyStaffRepository, runRepository, stopRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, managerRepository,
                assignmentRepository, routeRepository, routeStopRepository, weeklyAddressRepository);
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "첫생성학생");
        BoardingIntentFixtures.GuardianAccount mother = fixtures.guardian(academyId, "엄마");
        fixtures.linkChild(mother.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures.run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));
        long stopId = fixtures.stop(academyId, "37.564000", "126.974000");
        fixtures.enrol(academyId, busId, studentId, stopId);
        fixtures.confirmedSingleRiderStop(runId, studentId, stopId, now.minusMinutes(10));
        AuthUser requester = new AuthUser(mother.accountId(), academyId, Role.PARENT, AccountStatus.ACTIVE);

        CountDownLatch 먼저_넣었다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 먼저 = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbcTemplate.update("INSERT INTO boarding_intent (run_id, student_id, riding, change_used_count, "
                        + "created_at) VALUES (?, ?, true, 0, now())", runId, studentId);
                먼저_넣었다.countDown();
                상대가_대기할_때까지_커밋을_미룬다();
            }));
            Future<ErrorCode> 뒤 = pool.submit(() -> {
                먼저_넣었다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return toggleOff(requester, studentId, runId);
            });
            먼저.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
            assertThat(뒤.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)).isNull();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * BR-361 — ①구간에서 같은 값의 겹친 두 요청(더블탭)은 관계자 알림 이벤트가 한 번만 나간다. 순차 재전송은 BR-250 이 막지만, 두 요청이
     * 모두 {@code riding=true} 를 읽은 채 "이미 같은 값인가" 를 판정하면 둘 다 끄기로 통과해 같은 푸시가 두 번 간다. 이벤트 적재 직전에
     * 서로를 기다리게 해(잠금 대기가 보이면 앞 요청이 진행) 그 창을 결정적으로 만든다.
     */
    @Test
    void 같은_값을_겹쳐_토글해도_이벤트는_한_번만_나간다() throws Exception {
        겹침_준비 setup = 겹침을_준비한다("더블탭토글학생", "37.565000", "126.975000");
        AtomicInteger 도착 = new AtomicInteger();
        AtomicInteger 발행 = new AtomicInteger();
        doAnswer(invocation -> {
            IntentChangedEvent event = invocation.getArgument(0);
            if (event.runId() == setup.runId()) {
                발행.incrementAndGet();
                도착.incrementAndGet();
                상대가_도착하거나_잠금_대기할_때까지_기다린다(도착);
            }
            return invocation.callRealMethod();
        }).when(intentNotificationListener).appendIntentChanged(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> toggleOff(setup.requester(), setup.studentId(), setup.runId())));
            }
            for (Future<ErrorCode> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)).as("둘 다 정상 응답이다").isNull();
            }

            assertThat(발행.get()).as("값이 같은 겹친 요청이 이벤트를 두 번 내면 관계자에게 같은 푸시가 두 번 간다").isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** BR-361 — 변경 신청(취소)도 같다: 겹친 두 신청이 승인 행과 {@code absent} 통지를 둘 만들지 않고, 둘째는 앞의 승인 행을 돌려받는다. */
    @Test
    void 같은_취소를_겹쳐_신청해도_승인_행은_하나다() throws Exception {
        겹침_준비 setup = 겹침을_준비한다("더블탭취소학생", "37.566000", "126.976000");
        Student student = studentRepository.findById(setup.studentId()).orElseThrow();
        Run run = runRepository.findById(setup.runId()).orElseThrow();
        AtomicInteger 도착 = new AtomicInteger();
        AtomicInteger 발행 = new AtomicInteger();
        doAnswer(invocation -> {
            AbsentRecordedEvent event = invocation.getArgument(0);
            if (event.runId() == setup.runId()) {
                발행.incrementAndGet();
                도착.incrementAndGet();
                상대가_도착하거나_잠금_대기할_때까지_기다린다(도착);
            }
            return invocation.callRealMethod();
        }).when(intentNotificationListener).appendAbsent(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ChangeRequest>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(
                        () -> changeRequestStore.submitCancel(student, run, setup.requester().accountId(), null)));
            }
            List<Long> ids = new ArrayList<>();
            for (Future<ChangeRequest> result : results) {
                ids.add(result.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS).getId());
            }

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ? AND type = 'cancel'",
                    Integer.class, setup.runId(), setup.studentId())).as("겹친 재전송이 승인 행을 쌓는다").isEqualTo(1);
            assertThat(발행.get()).as("관계자 absent 통지도 한 번이다").isEqualTo(1);
            assertThat(ids).as("둘째는 앞의 승인 행을 돌려받는다").containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** ①구간(확정 90분 전)의 회차와 탑승 의사가 켜진 학생·보호자 — 겹침 시험이 같은 값 재전송을 만든다. */
    private 겹침_준비 겹침을_준비한다(String studentName, String lat, String lng) {
        BoardingIntentFixtures fixtures = new BoardingIntentFixtures(academyRepository, busRepository,
                studentRepository, guardianRepository, guardianStudentRepository, accountRepository,
                academyStaffRepository, runRepository, stopRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, managerRepository,
                assignmentRepository, routeRepository, routeStopRepository, weeklyAddressRepository);
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, studentName);
        BoardingIntentFixtures.GuardianAccount mother = fixtures.guardian(academyId, "엄마");
        fixtures.linkChild(mother.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures.run(academyId, busId, now.plusHours(2), now.plusMinutes(90));
        fixtures.enrol(academyId, busId, studentId, fixtures.stop(academyId, lat, lng));
        boardingIntentRepository.save(BoardingIntent.forRun(runId, studentId, now));
        return new 겹침_준비(runId, studentId,
                new AuthUser(mother.accountId(), academyId, Role.PARENT, AccountStatus.ACTIVE));
    }

    private record 겹침_준비(long runId, long studentId, AuthUser requester) {
    }

    private void 상대가_도착하거나_잠금_대기할_때까지_기다린다(AtomicInteger 도착) {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (도착.get() < 2 && !잠금_대기가_있다() && System.nanoTime() < 마감) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean 잠금_대기가_있다() {
        jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
        Integer 대기중 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                Integer.class);
        return 대기중 != null && 대기중 > 0;
    }

    private void 상대가_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시된다 — ChangeRequestAutoRejectionConcurrencyTest 와 같은 이유
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                    Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 성공이면 {@code null}, 업무 예외면 그 코드를 돌려준다 — 두 스레드의 결과를 한 목록으로 대조한다. */
    private ErrorCode toggleOff(AuthUser requester, long studentId, long runId) {
        try {
            boardingIntentCommandService.toggle(requester, studentId, runId, new BoardingIntentToggleRequest(false));
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }
}
