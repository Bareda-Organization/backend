package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.boarding.dto.RiderRevertRequest;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;

/**
 * 미승차 처리의 같은 {@code client_key} 재전송 — 응답의 {@code stop_skipped} 도 최초 처리 결과 그대로여야 한다(API_SPEC §1.7).
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class NoShowReplayStopSkippedTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    @Autowired
    private BoardingCommandService boardingCommandService;

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

    private record Stage(long runId, long stopId, AuthUser escort, long academyId) {
    }

    private Stage stage() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        fixtures.confirmedRunStop(runId, stopId, now.minusMinutes(30));
        return new Stage(runId, stopId, new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE),
                academyId);
    }

    private RiderStatusUpdateResponse noShow(Stage stage, long riderId, UUID clientKey) {
        return boardingCommandService.updateStatus(stage.escort(), stage.runId(), riderId,
                new RiderStatusUpdateRequest("no_show", "manual", clientKey, OffsetDateTime.now(clock)));
    }

    @Test
    @DisplayName("정차지의 마지막 탑승자를 미승차로 처리해 stop_skipped 가 true 였다면 같은 client_key 재전송도 true 다")
    void 마지막_탑승자_미승차_재전송은_stop_skipped_true_를_돌려준다() {
        Stage stage = stage();
        long riderId = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "혼자학생"), stage.stopId());
        UUID clientKey = UUID.randomUUID();

        RiderStatusUpdateResponse first = noShow(stage, riderId, clientKey);
        RiderStatusUpdateResponse replay = noShow(stage, riderId, clientKey);

        assertThat(first.stopSkipped()).as("정차지에 남은 사람이 0명이 됐으니 최초 응답은 true").isTrue();
        assertThat(replay.stopSkipped()).as("재전송은 최초 결과를 그대로 돌려준다").isTrue();
    }

    @Test
    @DisplayName("정차지에 다른 탑승자가 남아 stop_skipped 가 false 였다면 같은 client_key 재전송도 false 다")
    void 잔여_탑승자가_있는_미승차_재전송은_stop_skipped_false_를_돌려준다() {
        Stage stage = stage();
        long riderId = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "학생A"), stage.stopId());
        fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "학생B"), stage.stopId());
        UUID clientKey = UUID.randomUUID();

        RiderStatusUpdateResponse first = noShow(stage, riderId, clientKey);
        RiderStatusUpdateResponse replay = noShow(stage, riderId, clientKey);

        assertThat(first.stopSkipped()).isFalse();
        assertThat(replay.stopSkipped()).isFalse();
    }

    @Test
    @DisplayName("같은 정차지의 다른 탑승자가 뒤따라 미승차가 돼 정차지가 건너뜀이 된 뒤에도 먼저 보낸 요청의 재전송은 최초의 false 를 돌려준다")
    void 뒤따른_미승차가_정차지를_건너뜀으로_바꾼_뒤에도_재전송은_최초_결과다() {
        Stage stage = stage();
        long first = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "학생A"), stage.stopId());
        long second = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "학생B"), stage.stopId());
        UUID firstKey = UUID.randomUUID();

        RiderStatusUpdateResponse firstAnswer = noShow(stage, first, firstKey);
        RiderStatusUpdateResponse secondAnswer = noShow(stage, second, UUID.randomUUID());
        RiderStatusUpdateResponse replay = noShow(stage, first, firstKey);

        assertThat(firstAnswer.stopSkipped()).isFalse();
        assertThat(secondAnswer.stopSkipped()).as("뒤따른 요청이 잔여 0명을 만들었다").isTrue();
        assertThat(replay.stopSkipped()).as("재전송은 최초 응답을 돌려준다 — 정차지가 그 사이 건너뜀이 됐어도 false").isFalse();
    }

    @Test
    @DisplayName("마지막 탑승자의 미승차를 되돌려 건너뜀 표시가 풀린 뒤에도 먼저 보낸 요청의 재전송은 최초의 true 를 돌려준다")
    void 되돌려_건너뜀이_풀린_뒤에도_재전송은_최초_결과다() {
        Stage stage = stage();
        long riderId = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), "혼자학생"), stage.stopId());
        UUID clientKey = UUID.randomUUID();

        RiderStatusUpdateResponse firstAnswer = noShow(stage, riderId, clientKey);
        boardingCommandService.revert(stage.escort(), stage.runId(), riderId, new RiderRevertRequest("잘못 눌렀어요"));
        RiderStatusUpdateResponse replay = noShow(stage, riderId, clientKey);

        assertThat(firstAnswer.stopSkipped()).isTrue();
        assertThat(replay.stopSkipped()).as("재전송은 최초 응답을 돌려준다 — 되돌려 표시가 풀렸어도 true").isTrue();
    }
}
