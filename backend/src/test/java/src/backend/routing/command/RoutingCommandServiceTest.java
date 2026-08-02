package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.query.AttendanceQueryService;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Stop;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.engine.RoutePlanComputer;
import src.backend.routing.engine.spec.BusAssigner;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * 노선 계획 생성의 <b>특성 테스트</b>(characterization test) — BE-4 리팩터링(계산부를 RoutePlanComputer 로 분리)
 * 전후로 동작이 달라지지 않았음을 고정한다. 경로 계산 포트 2개는 외부 호출 없는 결정적 Fake 로 대체해
 * "최적화 품질"이 아니라 <b>정차 배치·ETA 오프셋·검사 순서·좌표 우선순위</b>를 검증한다.
 */
class RoutingCommandServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 10L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);

    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final AttendanceQueryService attendanceQueryService = mock(AttendanceQueryService.class);
    private final BusAssigner busAssigner = mock(BusAssigner.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    /** 삽입 순서를 그대로 돌려주는 결정적 엔진 — 최적화 품질이 아니라 배치·오프셋을 검증한다. */
    private final RouteEngine routeEngine = (depot, points) -> new ArrayList<>(points.keySet());
    private final FakeMapRouteClient mapRouteClient = new FakeMapRouteClient();
    private final RoutePlanComputer computer = new RoutePlanComputer(routeEngine, mapRouteClient, 7);

    private final RoutingCommandService service = new RoutingCommandService(
            routePlanRepository, busRepository, studentRepository, attendanceQueryService,
            busAssigner, eventPublisher, computer);

    /** 구간당 1000m·60s 고정 — 정차가 1개 줄면 델타가 정확히 −1000m/−60s 로 나온다. 외부 호출 없음. */
    static class FakeMapRouteClient implements MapRouteClient {
        final List<List<LatLng>> calls = new ArrayList<>();

        @Override
        public RouteResult route(List<LatLng> waypoints) {
            calls.add(List.copyOf(waypoints));
            int legs = waypoints.size() - 1;
            List<Double> legDurations = new ArrayList<>();
            for (int i = 0; i < legs; i++) {
                legDurations.add(60.0);
            }
            return new RouteResult(legs * 1000.0, legs * 60.0, legDurations, "[[37.5,127.0]]");
        }
    }

    @Test
    void generate_dropoff_stopsFollowOptimizedOrderWithDepotOffsetEta() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001),
                dropoffStudent(102L, 37.502, 127.002),
                dropoffStudent(103L, 37.503, 127.003));

        RoutePlanResponse response = service.generate(admin(), request(RouteDirection.DROPOFF));

        // waypoints = [depot, s101, s102, s103] → depot 이 waypoint[0] 이라 첫 정차 ETA 는 60초(0이 아니다)
        assertThat(response.stops()).extracting(RoutePlanResponse.StopEntry::studentId)
                .containsExactly(101L, 102L, 103L);
        assertThat(response.stops()).extracting(RoutePlanResponse.StopEntry::etaSeconds)
                .containsExactly(60L, 120L, 180L);
        assertThat(response.totalDistanceM()).isEqualTo(3000.0);
        assertThat(response.totalDurationS()).isEqualTo(180.0);
    }

    @Test
    void generate_pickup_reversesOrderAndFirstStopEtaIsZero() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(pickupStudent(101L, 37.501, 127.001),
                pickupStudent(102L, 37.502, 127.002),
                pickupStudent(103L, 37.503, 127.003));

        RoutePlanResponse response = service.generate(admin(), request(RouteDirection.PICKUP));

        // waypoints = [s103, s102, s101, depot] → 오프셋 0 이라 첫 정차 ETA 는 0초
        assertThat(response.stops()).extracting(RoutePlanResponse.StopEntry::studentId)
                .containsExactly(103L, 102L, 101L);
        assertThat(response.stops()).extracting(RoutePlanResponse.StopEntry::etaSeconds)
                .containsExactly(0L, 60L, 120L);
    }

    @Test
    void generate_rosterOverSeatCapacity_throwsInvalidInput() {
        Bus bus = bus(1, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));

        assertThatThrownBy(() -> service.generate(admin(), request(RouteDirection.DROPOFF)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void generate_tenantWithoutDepot_throwsDepotErrorEvenWhenAlsoOverCapacity() {
        Bus bus = bus(1, tenant(null, null));   // depot 미설정 + 정원 1 < 로스터 2 (두 조건 동시)
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));

        // 검사 순서 고정: depot 검사가 정원 초과보다 먼저 걸린다
        assertThatThrownBy(() -> service.generate(admin(), request(RouteDirection.DROPOFF)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("학원 위치(depot)");
    }

    @Test
    void generate_pickup_withoutPickupCoordinates_usesBoardingStopCoordinates() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(pickupStudent(101L, 37.400, 127.400));   // pickupLat/Lng 없음 → 정류장 좌표

        RoutePlanResponse response = service.generate(admin(), request(RouteDirection.PICKUP));

        assertThat(response.stops()).singleElement()
                .satisfies(stop -> {
                    assertThat(stop.lat()).isEqualTo(37.400);
                    assertThat(stop.lng()).isEqualTo(127.400);
                });
    }

    @Test
    void generate_pickup_withPickupCoordinates_prefersStudentCoordinates() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        Student student = pickupStudent(101L, 37.400, 127.400);
        ReflectionTestUtils.setField(student, "pickupLat", 37.510);
        ReflectionTestUtils.setField(student, "pickupLng", 127.510);
        givenRoster(student);

        RoutePlanResponse response = service.generate(admin(), request(RouteDirection.PICKUP));

        // D-K: 학생 자체 좌표가 있으면 공유 정류장보다 우선한다
        assertThat(response.stops()).singleElement()
                .satisfies(stop -> {
                    assertThat(stop.lat()).isEqualTo(37.510);
                    assertThat(stop.lng()).isEqualTo(127.510);
                });
    }

    // ── fixtures ──

    private void givenBus(Bus bus) {
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        given(routePlanRepository.save(any(RoutePlan.class))).willAnswer(inv -> inv.getArgument(0));
    }

    private void givenRoster(Student... students) {
        given(attendanceQueryService.getActiveRoster(BUS_ID, SERVICE_DATE)).willReturn(List.of(students));
    }

    private GenerateRoutePlanRequest request(RouteDirection direction) {
        return new GenerateRoutePlanRequest(BUS_ID, direction, SERVICE_DATE);
    }

    private Bus bus(int seatCapacity, Tenant tenant) {
        Bus bus = Bus.builder().tenant(tenant).name("3호차").seatCapacity(seatCapacity).build();
        ReflectionTestUtils.setField(bus, "id", BUS_ID);
        return bus;
    }

    private Tenant tenant(Double lat, Double lng) {
        Tenant tenant = Tenant.builder().name("한빛학원").lat(lat).lng(lng).build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private Student dropoffStudent(Long id, double lat, double lng) {
        Student student = Student.builder().tenant(tenant(37.500, 127.000)).name("학생" + id).build();
        ReflectionTestUtils.setField(student, "id", id);
        student.updateDropoff("하차지" + id, lat, lng);
        return student;
    }

    private Student pickupStudent(Long id, double stopLat, double stopLng) {
        Stop stop = Stop.builder().name("정류장" + id).seq(1).lat(stopLat).lng(stopLng).build();
        Student student = Student.builder().tenant(tenant(37.500, 127.000)).name("학생" + id)
                .boardingStop(stop).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private AuthUser admin() {
        return new AuthUser(100L, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }
}
