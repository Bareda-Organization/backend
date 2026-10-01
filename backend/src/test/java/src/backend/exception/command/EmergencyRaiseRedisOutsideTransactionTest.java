package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 비상 신고 접수는 위치 캐시(Redis)를 읽는 순간 DB 트랜잭션·연결·{@code client_key} 잠금이 없다(R46 T-5) — 비상은
 * 가장 빨라야 하는 쓰기라 Redis 가 느려(500ms 상한) 접수가 늦어지고, 그동안 연결과 같은 키의 재전송 잠금을 쥐면 안 된다.
 * 그러면서도 Redis 가 실패하면 위치 없이 접수하는 안전 요구(BR-039)는 그대로다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 시험이 트랜잭션을 열면 서비스가 합류해 "밖" 이 존재할 수 없다. 그래서 실제 커밋을
 * 남기고 만든 행을 {@link #뒷정리한다()} 가 직접 지운다({@code RunPositionRedisIntegrationTest} 와 같은 형태).
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class EmergencyRaiseRedisOutsideTransactionTest {

    @Autowired
    private EmergencyCommandService emergencyCommandService;

    @MockitoSpyBean
    private RunPositionStore runPositionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    /** 이 클래스가 만든 학원 id — 뒷정리가 이 값으로만 지운다(다른 시험의 행을 건드리지 않는다). */
    private final List<Long> academyIds = new ArrayList<>();

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** FK 순서: 신고·알림(FK 없음) → run(계단식 assignment) → manager → account → bus → academy. */
    @AfterEach
    void 뒷정리한다() {
        if (academyIds.isEmpty()) {
            return;
        }
        Object[] ids = academyIds.toArray(new Long[0]);
        jdbcTemplate.update("DELETE FROM emergency_alert WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", (Object) ids);
        academyIds.clear();
    }

    @Test
    @DisplayName("T-5 — 좌표 없는 비상 신고는 위치 캐시를 트랜잭션 밖에서 읽고, 읽은 좌표를 신고에 붙인다")
    void 위치_캐시는_트랜잭션_밖에서_읽고_신고에_붙인다() {
        MovingRun moving = 확정된_회차를_만든다();
        List<TxObservation> reads = new ArrayList<>();
        BigDecimal cachedLat = new BigDecimal("37.501000");
        BigDecimal cachedLng = new BigDecimal("127.001000");
        Mockito.doAnswer(invocation -> {
            reads.add(TxObservation.now());
            return Optional.of(new RunPositionRedisValue(cachedLat, cachedLng, now().minusSeconds(3), now(), "정차지"));
        }).when(runPositionStore).find(any());

        emergencyCommandService.raise(driverOf(moving), moving.runId(), 좌표_없는_신고());

        assertThat(reads).as("위치 캐시를 실제로 읽어야 한다").hasSize(1);
        assertThat(reads).as("읽는 시점에 트랜잭션·연결·EntityManager 가 없어야 한다")
                .allSatisfy(observation -> assertThat(observation.holdsNothing()).isTrue());
        assertThat(jdbcTemplate.queryForObject("SELECT lat FROM emergency_alert WHERE academy_id = ?", BigDecimal.class,
                moving.academyId())).as("읽은 좌표가 신고에 붙어야 한다").isEqualByComparingTo(cachedLat);
    }

    @Test
    @DisplayName("T-5 — 위치 캐시 읽기가 실패해도 신고는 위치 없이 접수된다(BR-039)")
    void 위치_캐시_읽기가_실패해도_위치_없이_접수된다() {
        MovingRun moving = 확정된_회차를_만든다();
        Mockito.doThrow(new IllegalStateException("redis down")).when(runPositionStore).find(any());

        emergencyCommandService.raise(driverOf(moving), moving.runId(), 좌표_없는_신고());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM emergency_alert WHERE academy_id = ?",
                Integer.class, moving.academyId())).as("신고는 반드시 접수된다").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT lat FROM emergency_alert WHERE academy_id = ?", BigDecimal.class,
                moving.academyId())).as("위치는 비어 있다").isNull();
    }

    private EmergencyRaiseRequest 좌표_없는_신고() {
        return new EmergencyRaiseRequest("accident", null, UUID.randomUUID(), null, null, null);
    }

    private AuthUser driverOf(MovingRun moving) {
        return new AuthUser(moving.driverAccountId(), moving.academyId(), Role.DRIVER, AccountStatus.ACTIVE);
    }

    private record MovingRun(long academyId, long runId, long driverAccountId) {
    }

    private MovingRun 확정된_회차를_만든다() {
        DriverRunFixtures fixtures = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        return new MovingRun(academyId, runId, driverAccountId);
    }

    /** 호출 시점에 이 스레드에 묶인 트랜잭션 자원 — {@code resources} 가 비어야 연결·EntityManager 가 없다. */
    private record TxObservation(boolean transactionActive, Map<Object, Object> resources) {

        static TxObservation now() {
            return new TxObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                    Map.copyOf(TransactionSynchronizationManager.getResourceMap()));
        }

        boolean holdsNothing() {
            return !transactionActive && resources.isEmpty();
        }
    }
}
