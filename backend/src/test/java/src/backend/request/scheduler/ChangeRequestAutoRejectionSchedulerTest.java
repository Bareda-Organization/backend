package src.backend.request.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

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

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.request.command.ChangeRequestAutoRejectFixtures;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 자동 거절 폴링({@link ChangeRequestAutoRejectionScheduler#rejectDueChangeRequests}) 수준의 검증
 * (Phase 8 T6 목표 1·5·6).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@link ChangeRequestAutoRejectionScheduler} 가 건마다
 * 별도 빈({@code ChangeRequestAutoRejectionPersistence})의 {@code @Transactional} 메서드를 호출하므로,
 * 테스트 스레드에 묶인 트랜잭션 롤백은 그 트랜잭션이 커밋한 것을 되돌리지 못한다
 * ({@code RunConfirmationSchedulerTest} 와 같은 근거). 뒷정리는
 * {@link ChangeRequestAutoRejectFixtures#ACADEMY_NAME} 로 표시된 행을 직접 지운다.
 */
@SpringBootTest
class ChangeRequestAutoRejectionSchedulerTest {

    @Autowired
    private ChangeRequestAutoRejectionScheduler scheduler;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private ChangeRequestAutoRejectFixtures fixtures;

    private OffsetDateTime now;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2031-04-01T03:00:00Z"); // 2031-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void setUp() {
        fixtures = new ChangeRequestAutoRejectFixtures(academyRepository, busRepository, studentRepository,
                accountRepository, runRepository, boardingIntentRepository, changeRequestRepository,
                confirmedRouteRepository, routeVersionRepository);
        now = OffsetDateTime.now(clock);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpMarkedRows();
    }

    /**
     * {@link ChangeRequestAutoRejectFixtures#ACADEMY_NAME} 로 표시된 행만 지운다 — {@code run} 삭제가
     * {@code change_request}·{@code confirmed_route}·{@code route_version}·{@code boarding_intent} 를
     * 이미 연쇄 삭제한다(V1 스키마, {@code ON DELETE CASCADE}). {@code notification_log} 는 FK 가 부재해
     * 별도로 지운다.
     */
    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME
                + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME + "'");
    }

    /** 학원·버스·학생·부모 계정·회차까지 갖춘 기본 시나리오 하나 — 각 시험이 세부 값만 덧붙인다. */
    private long[] baseScenario() {
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long parentId = fixtures.parentAccount(academyId, "학부모1");
        long runId = fixtures.run(academyId, busId, now.plusHours(2));
        return new long[] { academyId, runId, studentId, parentId };
    }

    @Test
    @DisplayName("목표1 — 마감이 지난 대기 요청을 폴링이 거절하고 세 결과를 함께 남긴다")
    void 마감이_지난_요청을_폴링이_거절한다() {
        long[] s = baseScenario();
        long academyId = s[0];
        long runId = s[1];
        long studentId = s[2];
        long parentId = s[3];

        long versionId = fixtures.confirmedRouteWithVersion(runId, now.minusHours(1));
        fixtures.spentBoardingIntent(runId, studentId, now.minusHours(1));
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentId,
                now.minusMinutes(10), now.minusMinutes(1));

        scheduler.rejectDueChangeRequests();

        var request = changeRequestRepository.findById(changeRequestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
        assertThat(request.getDecidedBy()).as("자동 거절은 서버가 한 일이라 처리자가 없어야 한다").isNull();

        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        assertThat(currentVersionId).as("재최적화를 부르지 않아 버전 포인터가 그대로여야 한다").isEqualTo(versionId);
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM route_version WHERE confirmed_route_id = ?", Integer.class, runId);
        assertThat(versionCount).as("새 버전 행이 만들어지지 않아야 한다").isEqualTo(1);

        Integer changeUsedCount = jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, studentId);
        assertThat(changeUsedCount).as("소비한 한도가 되돌아가야 한다").isZero();

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'change_decided'",
                Integer.class, parentId);
        assertThat(notificationCount).as("신청 학부모에게 change_decided 알림이 남아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("목표6 — 이미 처리된 요청은 폴링 질의에 다시 걸리지 않는다")
    void 이미_처리된_요청은_다시_걸리지_않는다() {
        long[] s = baseScenario();
        long academyId = s[0];
        long runId = s[1];
        long studentId = s[2];
        long parentId = s[3];

        fixtures.spentBoardingIntent(runId, studentId, now.minusHours(1));
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentId,
                now.minusMinutes(10), now.minusMinutes(1));
        // 관리자가 이미 승인했다고 가정 — 마감은 지났지만 더 이상 pending 이 아니다.
        jdbcTemplate.update("UPDATE change_request SET status = 'approved', decided_by = ?, decided_at = ? WHERE id = ?",
                parentId, now.minusMinutes(5), changeRequestId);

        scheduler.rejectDueChangeRequests();

        var request = changeRequestRepository.findById(changeRequestId).orElseThrow();
        assertThat(request.getStatus()).as("이미 처리된 요청은 상태가 바뀌면 안 된다").isEqualTo(ChangeRequestStatus.APPROVED);
        assertThat(request.getDecidedBy()).as("처리자 기록도 덮어써지면 안 된다").isEqualTo(parentId);

        Integer changeUsedCount = jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, studentId);
        assertThat(changeUsedCount).as("다시 집혔다면(=버그) 한도가 0으로 되돌아갔을 것이다 — 1이어야 미접근의 증거다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("목표5 — 한 건의 실패가 다른 건을 막지 않는다")
    void 한_건의_실패가_다른_건을_막지_않는다() {
        long[] good = baseScenario();
        long goodAcademyId = good[0];
        long goodRunId = good[1];
        long goodStudentId = good[2];
        long goodParentId = good[3];

        long badAcademyId = fixtures.academy();
        long badBusId = fixtures.bus(badAcademyId);
        long badStudentId = fixtures.student(badAcademyId, "학생2");
        long badParentId = fixtures.parentAccount(badAcademyId, "학부모2");
        long badRunId = fixtures.run(badAcademyId, badBusId, now.plusHours(2));

        fixtures.spentBoardingIntent(goodRunId, goodStudentId, now.minusHours(1));
        long goodRequestId = fixtures.pendingChangeRequest(goodAcademyId, goodRunId, goodStudentId, goodParentId,
                now.minusMinutes(10), now.minusMinutes(1));

        fixtures.spentBoardingIntent(badRunId, badStudentId, now.minusHours(1));
        long badRequestId = fixtures.pendingChangeRequest(badAcademyId, badRunId, badStudentId, badParentId,
                now.minusMinutes(10), now.minusMinutes(1));

        // 이 요청이 자동 거절되며 만들 dedup_key 를 미리 점유해, 알림 적재에서 DUPLICATE_NOTIFICATION 이
        // 나게 만든다 — ChangeRequestAutoRejectionPersistence 의 트랜잭션 전체가 롤백된다.
        String collidingDedupKey = "change_decided:" + badRunId + ":" + badStudentId + ":" + now;
        jdbcTemplate.update(
                "INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, "
                        + "type, title, body, dedup_key) VALUES (?, ?, '선점', 'parent', 'change_decided', '선점', '선점', ?)",
                badAcademyId, badParentId, collidingDedupKey);

        scheduler.rejectDueChangeRequests();

        var goodRequest = changeRequestRepository.findById(goodRequestId).orElseThrow();
        assertThat(goodRequest.getStatus()).as("옆 건이 실패해도 이 건은 거절돼야 한다")
                .isEqualTo(ChangeRequestStatus.AUTO_REJECTED);

        var badRequest = changeRequestRepository.findById(badRequestId).orElseThrow();
        assertThat(badRequest.getStatus()).as("알림 적재 실패로 이 건의 트랜잭션은 통째로 롤백돼야 한다")
                .isEqualTo(ChangeRequestStatus.PENDING);
        Integer badChangeUsedCount = jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                badRunId, badStudentId);
        assertThat(badChangeUsedCount).as("롤백됐다면 한도도 그대로 소비된 채여야 한다").isEqualTo(1);
    }
}
