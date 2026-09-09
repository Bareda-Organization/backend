package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@code moving} 종결({@link ChangeRequestAutoRejectionService#terminateForRun}) 수준의 검증
 * (Phase 8 T6 목표 2·3). 폴링 경로는 {@code ChangeRequestAutoRejectionSchedulerTest} 가 본다 — 이
 * 클래스는 <b>다른 진입점으로 불러도 같은 세 결과가 남는지</b>와 <b>마감 전에도 즉시 종결되는지</b>를 본다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@code terminateForRun} 이 건마다
 * {@code ChangeRequestAutoRejectionPersistence} 의 별도 트랜잭션을 여므로, 테스트 트랜잭션 롤백이
 * 그 커밋을 되돌리지 못한다({@code ChangeRequestAutoRejectionSchedulerTest} 와 같은 근거).
 */
@SpringBootTest
class ChangeRequestAutoRejectionServiceTest {

    @Autowired
    private ChangeRequestAutoRejectionService service;

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

    private ChangeRequestAutoRejectFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ChangeRequestAutoRejectFixtures(academyRepository, busRepository, studentRepository,
                accountRepository, runRepository, boardingIntentRepository, changeRequestRepository,
                confirmedRouteRepository, routeVersionRepository);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpMarkedRows();
    }

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

    @Test
    @DisplayName("목표2 — moving 종결도 폴링과 같은 세 결과를 남긴다")
    void moving_종결도_같은_결과를_남긴다() {
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long parentId = fixtures.parentAccount(academyId, "학부모1");
        long runId = fixtures.run(academyId, busId, now.plusMinutes(5));
        long versionId = fixtures.confirmedRouteWithVersion(runId, now.minusHours(1));
        fixtures.spentBoardingIntent(runId, studentId, now.minusHours(1));
        // deadline_at 은 아직 지나지 않았다 — moving 전이 자체가 종결 사유이지 마감 도달이 아니다.
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentId,
                now.minusMinutes(10), now.plusMinutes(4));

        service.terminateForRun(academyId, runId, now);

        var request = changeRequestRepository.findById(changeRequestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
        assertThat(request.getDecidedBy()).isNull();

        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        assertThat(currentVersionId).isEqualTo(versionId);

        Integer changeUsedCount = jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, studentId);
        assertThat(changeUsedCount).isZero();

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'change_decided'",
                Integer.class, parentId);
        assertThat(notificationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("목표3 — 출발 시각 도달과 moving 전이 중 먼저 오는 시점이 종결한다: moving 이 먼저면 즉시 종결된다")
    void moving_이_출발_전에_먼저_오면_즉시_종결된다() {
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long parentId = fixtures.parentAccount(academyId, "학부모1");
        // 출발까지 아직 1시간 남았다 — 폴링이라면 아직 대상이 아니다.
        long runId = fixtures.run(academyId, busId, now.plusHours(1));
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentId,
                now.minusMinutes(10), now.plusHours(1));

        service.terminateForRun(academyId, runId, now);

        var request = changeRequestRepository.findById(changeRequestId).orElseThrow();
        assertThat(request.getStatus()).as("마감까지 한참 남았어도 moving 전이가 먼저 오면 즉시 종결돼야 한다")
                .isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
    }
}
