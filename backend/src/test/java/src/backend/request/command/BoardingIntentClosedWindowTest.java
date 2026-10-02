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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import src.backend.boarding.entity.RunRider;
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
import src.backend.request.event.IntentChangedEvent;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 마감구간(③) 탑승 의사 토글의 탑승자 행 직렬화 — BR-303(방금 승차한 학생을 {@code absent} 로 덮던 결함)과
 * BR-325(잔여 0명 판정이 잠금 없이 세고 {@code no_show} 를 잔여로 세던 결함)의 재현본이다.
 *
 * <p>순서는 실제 DB 잠금 대기를 확인한 뒤 커밋시켜 고정한다 — 승하차 경로(BR-267)가 잠그는 행을 이 경로도 잠가야
 * 뒤 요청이 앞 요청의 커밋을 기다린다. {@code @Transactional} 을 쓰지 않는다(두 스레드가 각자 커밋해야 경합이 성립한다).
 */
@SpringBootTest
class BoardingIntentClosedWindowTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private BoardingIntentCommandService boardingIntentCommandService;

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

    private BoardingIntentFixtures fixtures;

    private final List<Long> academyIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new BoardingIntentFixtures(academyRepository, busRepository, studentRepository,
                guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                runRepository, stopRepository, confirmedRouteRepository, routeVersionRepository, runStopRepository,
                runRiderRepository, managerRepository, assignmentRepository, routeRepository, routeStopRepository,
                weeklyAddressRepository);
    }

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
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

    /** 마감구간(출발 시각이 이미 지난) 회차 하나와 그 정차지 하나 — 학생은 호출부가 {@link #rider} 로 얹는다. */
    private record ClosedRun(long academyId, long busId, long runId, long stopId) {
    }

    private ClosedRun closedRun(OffsetDateTime now, String lat, String lng) {
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        long runId = fixtures.run(academyId, busId, now.minusMinutes(5), now.minusMinutes(35));
        long stopId = fixtures.stop(academyId, lat, lng);
        return new ClosedRun(academyId, busId, runId, stopId);
    }

    /** 보호자가 연결된 학생을 만들고 그 회차 명단에 대기({@code waiting}) 탑승자로 올린다 — 첫 학생이면 확정 노선도 함께 쌓는다. */
    private BoardingIntentFixtures.GuardianAccount rider(ClosedRun run, long studentId, OffsetDateTime now,
            boolean firstOnStop) {
        BoardingIntentFixtures.GuardianAccount guardian = fixtures.guardian(run.academyId(), "보호자" + studentId);
        fixtures.linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        fixtures.enrol(run.academyId(), run.busId(), studentId, run.stopId());
        if (firstOnStop) {
            fixtures.confirmedSingleRiderStop(run.runId(), studentId, run.stopId(), now.minusHours(1));
        } else {
            runRiderRepository.save(RunRider.uponConfirmation(run.runId(), studentId, run.stopId()));
        }
        return guardian;
    }

    @Test
    @DisplayName("BR-303 — 승차가 먼저 커밋된 탑승자의 마감구간 미등원 토글은 403 CHANGE_WINDOW_CLOSED 이고 boarded 로 남는다")
    void 승차가_먼저_커밋되면_미등원_토글은_403_이고_boarded_로_남는다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        ClosedRun run = closedRun(now, "37.571000", "126.981000");
        long studentId = fixtures.student(run.academyId(), "승차학생");
        BoardingIntentFixtures.GuardianAccount guardian = rider(run, studentId, now, true);
        AuthUser requester = new AuthUser(guardian.accountId(), run.academyId(), Role.PARENT, AccountStatus.ACTIVE);

        CountDownLatch 승차가_행을_잡았다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 동승자 승하차(BR-267)와 같은 행 잠금을 잡고 boarded 를 쓴 채, 토글이 그 잠금에 막힌 것을 확인한 뒤 커밋한다
            Future<?> 승차 = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbcTemplate.queryForObject("SELECT id FROM run_rider WHERE run_id = ? AND student_id = ? FOR UPDATE",
                        Long.class, run.runId(), studentId);
                jdbcTemplate.update("UPDATE run_rider SET status = 'boarded' WHERE run_id = ? AND student_id = ?",
                        run.runId(), studentId);
                승차가_행을_잡았다.countDown();
                상대가_대기할_때까지_커밋을_미룬다();
            }));
            Future<ErrorCode> 토글 = pool.submit(() -> {
                승차가_행을_잡았다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return toggleOff(requester, studentId, run.runId());
            });
            승차.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);

            assertThat(토글.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS))
                    .as("승차가 먼저 커밋됐으면 토글은 거절돼야 한다 — null 이면 boarded 를 읽지 못한 채 absent 로 덮은 것이다")
                    .isEqualTo(ErrorCode.CHANGE_WINDOW_CLOSED);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE run_id = ? AND student_id = ?",
                String.class, run.runId(), studentId)).isEqualTo("boarded");
    }

    @Test
    @DisplayName("BR-325 — 같은 정차지의 나머지 탑승자가 no_show 뿐이면 마지막 대기 학생의 미등원으로 정차지는 skipped 이다")
    void 나머지가_미승차뿐이면_마지막_대기_학생의_미등원으로_정차지가_건너뜀이다() {
        OffsetDateTime now = OffsetDateTime.now();
        ClosedRun run = closedRun(now, "37.572000", "126.982000");
        long waitingStudent = fixtures.student(run.academyId(), "대기학생");
        BoardingIntentFixtures.GuardianAccount guardian = rider(run, waitingStudent, now, true);
        long noShowStudent = fixtures.student(run.academyId(), "미승차학생");
        long noShowRiderId = runRiderRepository
                .save(RunRider.uponConfirmation(run.runId(), noShowStudent, run.stopId())).getId();
        jdbcTemplate.update("UPDATE run_rider SET status = 'no_show' WHERE id = ?", noShowRiderId);

        toggleOff(new AuthUser(guardian.accountId(), run.academyId(), Role.PARENT, AccountStatus.ACTIVE),
                waitingStudent, run.runId());

        assertThat(stopChangeOf(run.runId())).as("남은 사람이 0명이 됐으니 그 정차지는 건너뜀 표시가 있어야 한다")
                .isEqualTo("skipped");
    }

    @Test
    @DisplayName("BR-325 — 같은 정차지의 마지막 두 학생이 동시에 미등원하면 어느 한쪽이 정차지를 skipped 로 세운다")
    void 마지막_두_학생이_동시에_미등원하면_정차지가_건너뜀이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        ClosedRun run = closedRun(now, "37.573000", "126.983000");
        long firstStudent = fixtures.student(run.academyId(), "학생A");
        long secondStudent = fixtures.student(run.academyId(), "학생B");
        BoardingIntentFixtures.GuardianAccount first = rider(run, firstStudent, now, true);
        BoardingIntentFixtures.GuardianAccount second = rider(run, secondStudent, now, false);

        // 두 요청 모두 잔여를 센 뒤(의사 변경 알림 직전) 서로를 기다린다 — 잠금이 없으면 둘 다 "1명 남음" 으로 센 채 도착하고,
        // 정차 항목 잠금이 있으면 뒤 요청은 거기서 멈춰 도착하지 못하므로 앞 요청은 상대의 잠금 대기를 보고 진행한다.
        AtomicInteger 도착 = new AtomicInteger();
        doAnswer(invocation -> {
            IntentChangedEvent event = invocation.getArgument(0);
            if (event.runId() == run.runId()) {
                도착.incrementAndGet();
                상대가_도착하거나_잠금_대기할_때까지_기다린다(도착);
            }
            return invocation.callRealMethod();
        }).when(intentNotificationListener).appendIntentChanged(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> results = new ArrayList<>();
            results.add(pool.submit(() -> toggleOff(
                    new AuthUser(first.accountId(), run.academyId(), Role.PARENT, AccountStatus.ACTIVE),
                    firstStudent, run.runId())));
            results.add(pool.submit(() -> toggleOff(
                    new AuthUser(second.accountId(), run.academyId(), Role.PARENT, AccountStatus.ACTIVE),
                    secondStudent, run.runId())));
            for (Future<ErrorCode> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_rider WHERE run_id = ? AND status = 'absent'",
                Integer.class, run.runId())).as("두 명 다 미등원으로 반영됐다").isEqualTo(2);
        assertThat(stopChangeOf(run.runId())).as("남은 사람이 0명이 됐으니 그 정차지는 건너뜀 표시가 있어야 한다")
                .isEqualTo("skipped");
    }

    /** 확정 노선 현재 버전의 정차 항목 {@code change} — 이 시험의 회차는 정차지가 하나뿐이다. */
    private String stopChangeOf(long runId) {
        return jdbcTemplate.queryForObject("SELECT change FROM run_stop WHERE route_version_id IN "
                + "(SELECT current_version_id FROM confirmed_route WHERE run_id = ?)", String.class, runId);
    }

    private void 상대가_도착하거나_잠금_대기할_때까지_기다린다(AtomicInteger 도착) {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (도착.get() < 2 && !잠금_대기가_있다() && System.nanoTime() < 마감) {
            잠깐_쉰다();
        }
    }

    private void 상대가_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!잠금_대기가_있다() && System.nanoTime() < 마감) {
            잠깐_쉰다();
        }
    }

    /** pg_stat_activity 는 트랜잭션 단위로 캐시된다 — BoardingIntentQuotaConcurrencyTest 와 같은 이유로 매번 비운다. */
    private boolean 잠금_대기가_있다() {
        jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
        Integer 대기중 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                Integer.class);
        return 대기중 != null && 대기중 > 0;
    }

    private static void 잠깐_쉰다() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
