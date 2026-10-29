package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.command.BoardingCommandService;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.exception.dto.ExceptionReportCreateRequest;
import src.backend.exception.dto.ExceptionReportCreateResponse;
import src.backend.exception.dto.NoShowContactRequest;
import src.backend.exception.dto.NoShowContactResponse;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;
import testsupport.concurrency.Race;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 같은 {@code client_key} 의 동시 재전송 — 예외 보고와 미승차 연락 시도 모두 한 행만 남기고 두 요청 모두 같은 결과로 답한다(API_SPEC §1.7).
 *
 * <p>두 요청이 {@code findByClientKey} 를 <b>둘 다 마친 뒤에야</b> 쓰기로 넘어가게 배리어를 세운다. 키 잠금이 읽기 전에 요청을 직렬화하면 뒤 요청은
 * 배리어에 오지 못하고 앞 요청이 시간 만료로 지나간다. {@code @Transactional} 을 쓰지 않는다 — 서로의 커밋을 못 보는 경합이 사라지기 때문이다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class ClientKeyResendConcurrencyTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    private static final long BARRIER_LIMIT_SECONDS = 3;

    @Autowired
    private ExceptionReportCommandService exceptionReportCommandService;

    @Autowired
    private NoShowContactCommandService noShowContactCommandService;

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
        RepositoryReadHooks.clear();
        AcademyRowsCleaner.delete(jdbcTemplate, ACADEMY_NAME);
    }

    private static void meetOrGiveUp(CyclicBarrier barrier) {
        try {
            barrier.await(BARRIER_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception 시간_만료_또는_배리어_깨짐) {
            // 상대를 더 기다리지 않고 진행한다.
        }
    }

    private record Stage(long runId, long riderId, AuthUser escort) {
    }

    private Stage movingRunWithRider() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long riderId = fixtures.runRider(runId, fixtures.student(academyId, "재전송학생"), stopId);
        return new Stage(runId, riderId, new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE));
    }

    @Test
    @DisplayName("같은 client_key 로 예외 보고를 동시에 두 번 보내도 두 요청이 같은 보고를 답하고 행은 1개다")
    void 같은_client_key_예외_보고_동시_재전송은_첫_결과를_돌려준다() throws Exception {
        Stage stage = movingRunWithRider();
        UUID clientKey = UUID.randomUUID();
        ExceptionReportCreateRequest request = new ExceptionReportCreateRequest("etc", "도로 공사", null, clientKey);
        CyclicBarrier bothMissedReplay = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("findByClientKey", () -> meetOrGiveUp(bothMissedReplay));
        AtomicReference<ExceptionReportCreateResponse> first = new AtomicReference<>();
        AtomicReference<ExceptionReportCreateResponse> second = new AtomicReference<>();

        List<Throwable> failures = Race.run(
                () -> first.getAndSet(exceptionReportCommandService.report(stage.escort(), stage.runId(), request)),
                () -> second.getAndSet(exceptionReportCommandService.report(stage.escort(), stage.runId(), request)));

        assertThat(failures).as("뒤 요청이 UNIQUE 위반 500 이 아니라 재생 응답이어야 한다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM exception_report WHERE client_key = ?",
                Integer.class, clientKey)).isEqualTo(1);
        assertThat(first.get().reportId()).isEqualTo(second.get().reportId());
        assertThat(List.of(first.get().replayed(), second.get().replayed())).containsExactlyInAnyOrder(false, true);
    }

    @Test
    @DisplayName("같은 client_key 로 미승차 연락 시도를 동시에 두 번 보내도 두 요청이 같은 시도를 답하고 행은 1개다")
    void 같은_client_key_연락_시도_동시_재전송은_첫_결과를_돌려준다() throws Exception {
        Stage stage = movingRunWithRider();
        boardingCommandService.updateStatus(stage.escort(), stage.runId(), stage.riderId(),
                new RiderStatusUpdateRequest("no_show", "manual", UUID.randomUUID(), OffsetDateTime.now(clock)));
        UUID clientKey = UUID.randomUUID();
        NoShowContactRequest request = new NoShowContactRequest("call", "no_answer", null, clientKey);
        CyclicBarrier bothMissedReplay = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("findByClientKey", () -> meetOrGiveUp(bothMissedReplay));
        AtomicReference<NoShowContactResponse> first = new AtomicReference<>();
        AtomicReference<NoShowContactResponse> second = new AtomicReference<>();

        List<Throwable> failures = Race.run(
                () -> first.getAndSet(noShowContactCommandService.recordAttempt(stage.escort(), stage.runId(),
                        stage.riderId(), request)),
                () -> second.getAndSet(noShowContactCommandService.recordAttempt(stage.escort(), stage.runId(),
                        stage.riderId(), request)));

        assertThat(failures).as("뒤 요청이 UNIQUE 위반 500 이 아니라 재생 응답이어야 한다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE client_key = ?",
                Integer.class, clientKey)).isEqualTo(1);
        assertThat(first.get().attemptedAt()).isEqualTo(second.get().attemptedAt());
        assertThat(List.of(first.get().replayed(), second.get().replayed())).containsExactlyInAnyOrder(false, true);
    }
}
