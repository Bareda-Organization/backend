package src.backend.run.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.domain.VerifiedAddressEntry;
import src.backend.student.entity.WeeklyAddress;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * BR-046 — §5.19 예정 경로 계산은 외부 지도 API 를 부른다. 그 호출이 읽기 트랜잭션 안에 있으면 지도가 느린
 * 만큼 DB 커넥션을 쥔 채 기다리고, 관리자가 확정 전 버스를 연달아 누르면 확정 배치와 같은 풀이 묶인다.
 *
 * <p>기능 시험은 스텁 지도로 즉시 끝나 이 차이를 못 본다 — 그래서 지도 호출 <b>순간</b>에 트랜잭션이
 * 열려 있는지를 기록해 본다. {@code @Transactional} 을 쓰지 않는다(쓰면 시험 트랜잭션이 늘 열려 있다).
 */
@SpringBootTest
class StaffRunRouteTransactionTest {

    @MockitoSpyBean private MapRouteClient mapRouteClient;
    @Autowired private StaffRunRouteQueryService staffRunRouteQueryService;
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
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM route WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("BR-046 — 확정 전 회차의 예정 경로를 계산할 때 지도 호출은 트랜잭션 밖에서 일어난다")
    void 예정_경로의_지도_호출은_트랜잭션_밖이다() {
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fx.academy();
        academyIds.add(academyId);
        Academy academy = academyRepository.findById(academyId).orElseThrow();
        academy.assignCoordinates(new BigDecimal("37.500000"), new BigDecimal("127.000000"));
        academyRepository.save(academy);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.510000", "127.010000");
        Route route = routeRepository
                .save(Route.register(academyId, new RoutePlan(busId, Weekday.MON, Direction.TO_ACADEMY, "본선", true)));
        routeStopRepository.save(RouteStop.forRoute(route.getId(), stopId, 1));
        long studentId = fx.student(academyId, "학생1");
        weeklyAddressRepository.save(WeeklyAddress.verified(studentId, new VerifiedAddressEntry(
                new VerifiedAddressEntry.AddressSlot(Weekday.MON, Direction.TO_ACADEMY),
                new VerifiedAddressEntry.AddressText("서울시 어딘가 37.51", null),
                new GeocodedPoint(new BigDecimal("37.510000"), new BigDecimal("127.010000"), "서울시 어딘가")),
                stopId, OffsetDateTime.now()));
        long runId = fx.idleRun(academyId, busId, Direction.TO_ACADEMY, OffsetDateTime.now().plusHours(3));

        List<Boolean> transactionActiveAtCall = new ArrayList<>();
        doAnswer(invocation -> {
            transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(mapRouteClient).route(any());

        staffRunRouteQueryService.route(new AuthUser(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE), runId);

        assertThat(transactionActiveAtCall).as("지도 호출이 한 번은 일어나야 이 검사가 의미가 있다").isNotEmpty();
        assertThat(transactionActiveAtCall).as("지도 호출 순간 DB 트랜잭션(커넥션)이 열려 있으면 안 된다")
                .containsOnly(false);
    }
}
