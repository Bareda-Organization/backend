package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.scheduler.NoShowEscalationFixtures;
import src.backend.exception.scheduler.NoShowEscalationScheduler;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.scheduler.NotificationOutboxWorker;
import src.backend.request.command.ChangeRequestAutoRejectFixtures;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.request.scheduler.ChangeRequestAutoRejectionScheduler;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 카운터 3종(관측 목표 3, Phase 14 이월 ④)이 <b>실제 이벤트 경로</b>로 오르는지 본다 —
 * {@code metrics.recordX()} 를 직접 부르는 시험은 계측 클래스 자신의 산술만 검증할 뿐, 그 카운터를
 * 올리는 스케줄러·워커 쪽 연결이 빠져도 그대로 통과한다.
 *
 * <p>세 카운터가 도메인이 서로 달라({@code notification}·{@code request}·{@code exception}) 한
 * 클래스에 묶었다 — {@code observability/metrics} 소유 범위 안에서 각 도메인의 스케줄러를 그대로
 * 호출하는 통합 시험이라, 도메인별로 시험 클래스를 나누면 학원·회차 배선만 세 번 반복된다.
 *
 * <p>세 시험 모두 <b>증분(delta)</b>으로 단언한다 — 세 카운터 모두 기동 직후부터 등록돼 있어 이 시험
 * 밖에서 이미 오른 값이 섞여 있을 수 있다({@code RunUnconfirmedGaugeSchedulerTest} 와 같은 근거).
 */
@SpringBootTest
class DomainEventCounterWiringTest {

    private static final Instant FIXED = Instant.parse("2033-04-01T03:00:00Z"); // 2033-04-01 12:00 KST

    /** 시드의 유일한 미발송 {@code notification_log} 행({@code V2__seed_data.sql}). */
    private static final long 시드_미발송_알림 = 4L;

    /** 시드의 유일한 대기중 {@code change_request} 행 — 마감이 실제 벽시계로 곧 지난다(실측 확인). */
    private static final long 시드_대기중_변경요청 = 1L;

    @Autowired
    private NotificationOutboxWorker notificationOutboxWorker;

    @Autowired
    private ChangeRequestAutoRejectionScheduler changeRequestAutoRejectionScheduler;

    @Autowired
    private NoShowEscalationScheduler noShowEscalationScheduler;

    @MockitoBean
    private PushSender pushSender;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private BoardingIntentRepository boardingIntentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private NoShowCaseRepository noShowCaseRepository;

    private ChangeRequestAutoRejectFixtures changeRequestFixtures;

    private NoShowEscalationFixtures noShowFixtures;

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void setUp() {
        changeRequestFixtures = new ChangeRequestAutoRejectFixtures(academyRepository, busRepository,
                studentRepository, accountRepository, runRepository, boardingIntentRepository,
                changeRequestRepository, confirmedRouteRepository, routeVersionRepository);
        noShowFixtures = new NoShowEscalationFixtures(academyRepository, academyStaffRepository, busRepository,
                studentRepository, stopRepository, accountRepository, runRepository, runRiderRepository,
                noShowCaseRepository);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpMarkedRows();
    }

    private void cleanUpMarkedRows() {
        String crAcademyIds =
                "(SELECT id FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + crAcademyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + crAcademyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + crAcademyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + crAcademyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + crAcademyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME + "'");

        String nsAcademyIds = "(SELECT id FROM academy WHERE name = '" + NoShowEscalationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + nsAcademyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + NoShowEscalationFixtures.ACADEMY_NAME + "'");

        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'f1s1counter:%'");
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    @Test
    @DisplayName("발송 시도가 상한을 소진해 failed 로 굳으면 schoolbus.notification.push.failures 가 오른다")
    void 발송_상한_소진시_실패_카운터가_오른다() {
        // 시드의 유일한 pending 행(id=4)이 같은 회수 질의에 함께 걸리면 이 시험만의 순증분이 깨진다
        // — 회수 대상에서 잠시 빼 두고 되돌린다(NotificationOutboxWorkerTest 와 같은 방식).
        jdbcTemplate.update("UPDATE notification_log SET push_state = 'sent' WHERE id = ?", 시드_미발송_알림);
        String dedupKey = "f1s1counter:" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name,
                        recipient_role, type, title, body, push_state, push_attempts, last_attempt_at,
                        dedup_key, created_at)
                VALUES (1, 4005, '조대기', 'parent', 'signup_decided', '가입 심사 안내',
                        '가입 심사 결과가 나왔습니다.', 'pending', 2, NULL, ?, now())
                """, dedupKey);
        try {
            doThrow(new RuntimeException("F1 S1 음성 대조 — 항상 실패")).when(pushSender).send(any());

            double before = counter("schoolbus.notification.push.failures");
            notificationOutboxWorker.sweep();
            double after = counter("schoolbus.notification.push.failures");

            assertThat(after - before).as("이 시험이 심은 1건만 실패로 굳어야 한다").isEqualTo(1.0);

            String pushState = jdbcTemplate.queryForObject(
                    "SELECT push_state FROM notification_log WHERE dedup_key = ?", String.class, dedupKey);
            assertThat(pushState).as("3번째 시도(상한)까지 실패했으니 failed 로 굳어야 한다").isEqualTo("failed");
        } finally {
            jdbcTemplate.update("""
                    UPDATE notification_log
                       SET push_state = 'pending', push_attempts = 0, last_attempt_at = NULL,
                           sent_at = NULL, fail_reason = NULL
                     WHERE id = ?
                    """, 시드_미발송_알림);
        }
    }

    @Test
    @DisplayName("마감이 지난 대기 변경 요청을 자동 거절하면 schoolbus.change_request.auto_rejected 가 오른다")
    void 마감_지난_변경요청_자동거절시_카운터가_오른다() {
        // 시드의 유일한 pending 행(id=1)의 deadline_at 이 실제 벽시계 기준 곧 지난다(실측 확인,
        // 2026-09-04 db_now 대비 +6분) — 이 시험의 고정 시계(2033년)는 그보다 훨씬 뒤라 함께 걸린다.
        // status 를 잠시 승인 처리해 폴링 대상에서 빼고, 시험이 끝나면 대기중으로 되돌린다.
        jdbcTemplate.update("UPDATE change_request SET status = 'approved' WHERE id = ?", 시드_대기중_변경요청);
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            long academyId = changeRequestFixtures.academy();
            long busId = changeRequestFixtures.bus(academyId);
            long runId = changeRequestFixtures.run(academyId, busId, now.plusHours(2));
            long studentId = changeRequestFixtures.student(academyId, "학생1");
            long parentAccountId = changeRequestFixtures.parentAccount(academyId, "학부모1");
            long changeRequestId = changeRequestFixtures.pendingChangeRequest(academyId, runId, studentId,
                    parentAccountId, now.minusHours(1), now.minusMinutes(1));

            double before = counter("schoolbus.change_request.auto_rejected");
            changeRequestAutoRejectionScheduler.rejectDueChangeRequests();
            double after = counter("schoolbus.change_request.auto_rejected");

            assertThat(after - before).as("이 시험이 심은 1건만 자동 거절돼야 한다").isEqualTo(1.0);

            assertThat(changeRequestRepository.findById(changeRequestId).orElseThrow().getStatus())
                    .as("마감이 지난 대기 요청은 auto_rejected 로 넘어가야 한다")
                    .isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
        } finally {
            jdbcTemplate.update("UPDATE change_request SET status = 'pending' WHERE id = ?", 시드_대기중_변경요청);
        }
    }

    @Test
    @DisplayName("대기가 만료된 미승차 케이스를 에스컬레이션하면 schoolbus.no_show.escalated 가 오른다")
    void 대기_만료된_미승차_케이스_에스컬레이션시_카운터가_오른다() {
        long academyId = noShowFixtures.academy();
        noShowFixtures.staffAccount(academyId);
        long busId = noShowFixtures.bus(academyId);
        long stopId = noShowFixtures.stop(academyId);
        long studentId = noShowFixtures.student(academyId, "학생1");
        OffsetDateTime now = OffsetDateTime.now(clock);
        long runId = noShowFixtures.run(academyId, busId, now.plusHours(2));
        long riderId = noShowFixtures.runRider(runId, studentId, stopId);
        long caseId = noShowFixtures.noShowCase(riderId, now.minusMinutes(10), now.minusMinutes(1));

        double before = counter("schoolbus.no_show.escalated");
        noShowEscalationScheduler.escalateDueNoShowCases();
        double after = counter("schoolbus.no_show.escalated");

        assertThat(after - before).as("이 시험이 심은 1건만 에스컬레이션돼야 한다").isEqualTo(1.0);

        assertThat(noShowCaseRepository.findById(caseId).orElseThrow().getEscalatedAt())
                .as("대기 만료된 케이스는 escalated_at 이 채워져야 한다")
                .isNotNull();
    }
}
