package src.backend.routing.query;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.Route;
import src.backend.student.entity.Stop;
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
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import src.backend.global.common.enums.Weekday;

/**
 * BR-211 — {@code GET /staff/routes/{id}/path} 는 외부 지도 API 를 부른다. 그 호출이 읽기 트랜잭션 안에 있으면 지도가
 * 느린 만큼 DB 커넥션을 쥔 채 기다려, 관리자 몇 명이 편성 화면을 열면 커넥션 풀이 그 시간만큼 묶인다
 * ({@code StaffRunRouteTransactionTest}(BR-046)와 같은 형태 — 지도 호출 <b>순간</b>의 트랜잭션 여부를 기록한다).
 */
@SpringBootTest
class RoutePathTransactionTest {

    @MockitoSpyBean private MapRouteClient mapRouteClient;
    @Autowired private RoutePathQueryService routePathQueryService;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long academyId;

    @AfterEach
    void 뒷정리한다() {
        if (academyId != null) {
            jdbcTemplate.update("DELETE FROM route WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
    }

    @Test
    @DisplayName("BR-211 — 편성 도로 경로의 지도 호출은 트랜잭션 밖에서 일어난다")
    void 편성_경로의_지도_호출은_트랜잭션_밖이다() {
        Academy academy = academyRepository.save(Academy.register("BR211-" + uniqueSuffix(), "경로트랜잭션시험학원",
                "서울", null, null));
        academyId = academy.getId();
        academy.assignCoordinates(new BigDecimal("37.500000"), new BigDecimal("127.000000"));
        academyRepository.save(academy);
        long busId = busRepository.save(Bus.register(academyId, "BR211-" + uniqueSuffix(), "22나1234",
                BusSeating.withDefaultCrew(16))).getId();
        Route route = routeRepository
                .save(Route.register(academyId, new RoutePlan(busId, Weekday.MON, Direction.TO_ACADEMY, "본선", true)));
        for (int seq = 1; seq <= 2; seq++) {
            String lat = "37.51000" + seq;
            long stopId = stopRepository.save(Stop.forVerifiedAddress(academyId, "정차지" + seq, "서울시 어딘가 " + lat,
                    new BigDecimal(lat), new BigDecimal("127.010000"))).getId();
            routeStopRepository.save(RouteStop.forRoute(route.getId(), stopId, seq));
        }

        List<Boolean> transactionActiveAtCall = new ArrayList<>();
        doAnswer(invocation -> {
            transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(mapRouteClient).route(any());

        routePathQueryService.path(new AuthUser(1L, academyId, Role.STAFF, AccountStatus.ACTIVE), route.getId());

        assertThat(transactionActiveAtCall).as("지도 호출이 한 번은 일어나야 이 검사가 의미가 있다").isNotEmpty();
        assertThat(transactionActiveAtCall).as("지도 호출 순간 DB 트랜잭션(커넥션)이 열려 있으면 안 된다")
                .containsOnly(false);
    }

    /** 시험 행 이름 뒤에 붙이는 짧은 고유값 — {@code System.nanoTime()} 은 기기 가동 시간이 길면 자릿수가 늘어 {@code varchar(20)} 을 넘는다(2026-10-01 실측 · 가동 약 28시간부터). */
    private static String uniqueSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
