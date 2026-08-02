package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.roster.ActiveRosterReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.dto.StudentOverride;
import src.backend.routing.engine.RoutePlanComputer;
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
 * 배차 변경안 시뮬레이션 단위 테스트 — 저장 0건(I-3)·델타 부호(candidate − baseline)·
 * 정원 초과를 예외로 죽이지 않는 규칙·인가 위치(①에만 있고 ②에는 없다)를 검증한다.
 */
class RoutePlanSimulationServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;
    private static final Long BUS_ID = 10L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);

    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ActiveRosterReader activeRosterReader = mock(ActiveRosterReader.class);

    /** 삽입 순서를 그대로 돌려주는 결정적 엔진 — 최적화 품질이 아니라 배치·오프셋을 검증한다. */
    private final RouteEngine routeEngine = (depot, points) -> new ArrayList<>(points.keySet());
    private final FakeMapRouteClient mapRouteClient = new FakeMapRouteClient();
    private final RoutePlanComputer computer = new RoutePlanComputer(routeEngine, mapRouteClient, 7);

    private final RoutePlanSimulationService service = new RoutePlanSimulationService(
            routePlanRepository, busRepository, studentRepository, activeRosterReader, computer);

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
    void compare_removeOverride_candidateHasOneFewerStopThanRoster() {
        givenBus(bus(25, TENANT_ID));
        givenRoster(dropoffStudent(101L), dropoffStudent(102L), dropoffStudent(103L));

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE,
                List.of(remove(102L)));

        assertThat(result.candidate().stops()).hasSize(2);
        assertThat(result.candidate().stops()).extracting(RoutePlanComparison.StopView::studentId)
                .containsExactly(101L, 103L);
        assertThat(result.candidate().stops()).extracting(RoutePlanComparison.StopView::studentName)
                .containsExactly("학생101", "학생103");
        assertThat(result.candidate().stops()).extracting(RoutePlanComparison.StopView::label)
                .containsExactly("하차지101", "하차지103");
        assertThat(result.candidate().routePlanId()).isNull();   // 미저장이라 id·version 은 항상 null
        assertThat(result.candidate().version()).isNull();
    }

    @Test
    void compare_deltaIsCandidateMinusBaseline() {
        givenBus(bus(25, TENANT_ID));
        givenRoster(dropoffStudent(101L), dropoffStudent(102L), dropoffStudent(103L), dropoffStudent(104L));
        givenBaseline(baselinePlan(4, 4000.0, 240.0, 101L, 102L, 103L, 104L));

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE,
                List.of(remove(104L)));

        // candidate: 정차 3 → waypoints [depot,s,s,s] = 3구간 = 3000m/180s
        assertThat(result.candidate().totalDistanceM()).isEqualTo(3000.0);
        assertThat(result.candidate().totalDurationS()).isEqualTo(180.0);
        assertThat(result.delta().distanceM()).isEqualTo(-1000.0);
        assertThat(result.delta().durationS()).isEqualTo(-60.0);
        assertThat(result.delta().stopCount()).isEqualTo(-1);
        assertThat(result.baseline().routePlanId()).isEqualTo(500L);
        assertThat(result.baseline().version()).isEqualTo(4);
    }

    @Test
    void compare_noBaselinePlan_deltaIsNullButSeatCapacityIsSet() {
        givenBus(bus(25, TENANT_ID));
        givenRoster(dropoffStudent(101L), dropoffStudent(102L));
        given(routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(BUS_ID, RouteDirection.DROPOFF))
                .willReturn(Optional.empty());

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, List.of());

        assertThat(result.baseline()).isNull();
        assertThat(result.delta()).isNull();
        assertThat(result.seatCapacity()).isEqualTo(25);   // baseline 유무와 무관하게 항상 채운다
    }

    @Test
    void compare_overSeatCapacity_returnsComparisonWithoutThrowing() {
        givenBus(bus(2, TENANT_ID));   // 정원 2 < 로스터 3
        givenRoster(dropoffStudent(101L), dropoffStudent(102L), dropoffStudent(103L));

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, List.of());

        // "보여주기는 관대" — 정원 초과 판정은 화면이 한다
        assertThat(result.candidate().stops()).hasSize(3);
        assertThat(result.seatCapacity()).isEqualTo(2);
    }

    /**
     * I-9 회귀 — override 는 ActiveRosterReader.forBus 를 거치지 않고 학생을 직접 주입하므로,
     * 여기서 막지 않으면 퇴원(active=false) 학생이 시뮬레이션 결과에 되살아난다.
     */
    @Test
    void compare_addOverrideOnInactiveStudent_throwsInvalidInput() {
        givenBus(bus(25, TENANT_ID));
        givenRoster(dropoffStudent(101L));
        Student inactive = dropoffStudent(999L);
        inactive.deactivate();
        given(studentRepository.findById(999L)).willReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE,
                List.of(add(999L, 37.51, 127.03))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 반대로 REMOVE 는 비활성이어도 통과해야 한다 — 빼는 것은 언제나 안전하고, 막으면 정리를 할 수 없다. */
    @Test
    void compare_removeOverrideOnInactiveStudent_isAllowed() {
        givenBus(bus(25, TENANT_ID));
        Student inactive = dropoffStudent(102L);
        inactive.deactivate();
        givenRoster(dropoffStudent(101L), inactive);

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE,
                List.of(remove(102L)));

        assertThat(result.candidate().stops()).extracting(RoutePlanComparison.StopView::studentId)
                .containsExactly(101L);
    }

    @Test
    void compare_savesNothing() {
        givenBus(bus(25, TENANT_ID));
        givenRoster(dropoffStudent(101L), dropoffStudent(102L));

        service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, List.of(remove(102L)));

        verify(routePlanRepository, never()).save(any());
        verify(studentRepository, never()).save(any());
    }

    @Test
    void compare_doesNotCheckAuthorization() {
        givenBus(bus(25, OTHER_TENANT_ID));   // 다른 학원 버스여도 ②는 막지 않는다 — 인가는 호출자 책임
        givenRoster(dropoffStudent(101L), dropoffStudent(102L));

        RoutePlanComparison result = service.compare(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, List.of());

        assertThat(result.candidate().stops()).hasSize(2);
    }

    @Test
    void simulate_busInOtherTenant_throwsForbidden() {
        givenBus(bus(25, OTHER_TENANT_ID));
        givenRoster(dropoffStudent(101L));
        SimulateRoutePlanRequest req =
                new SimulateRoutePlanRequest(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, List.of());

        assertThatThrownBy(() -> service.simulate(admin(), req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── fixtures ──

    private void givenBus(Bus bus) {
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
    }

    private void givenRoster(Student... students) {
        given(activeRosterReader.forBus(BUS_ID, SERVICE_DATE)).willReturn(List.of(students));
        given(studentRepository.findAllById(any())).willReturn(List.of(students));
    }

    private void givenBaseline(RoutePlan plan) {
        given(routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(BUS_ID, RouteDirection.DROPOFF))
                .willReturn(Optional.of(plan));
    }

    private StudentOverride remove(Long studentId) {
        return new StudentOverride(studentId, StudentOverride.OverrideAction.REMOVE, null, null);
    }

    private StudentOverride add(Long studentId, double lat, double lng) {
        return new StudentOverride(studentId, StudentOverride.OverrideAction.ADD, lat, lng);
    }

    private RoutePlan baselinePlan(int version, double distanceM, double durationS, Long... studentIds) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(BUS_ID).direction(RouteDirection.DROPOFF)
                .status(RoutePlanStatus.PUBLISHED).version(version).serviceDate(SERVICE_DATE)
                .polyline("[[37.5,127.0]]").totalDistanceM(distanceM).totalDurationS(durationS)
                .build();
        ReflectionTestUtils.setField(plan, "id", 500L);
        long eta = 60;
        for (Long studentId : studentIds) {
            plan.addStop(studentId, 37.5, 127.0, eta);
            eta += 60;
        }
        return plan;
    }

    private Bus bus(int seatCapacity, Long tenantId) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("3호차").seatCapacity(seatCapacity).build();
        ReflectionTestUtils.setField(bus, "id", BUS_ID);
        return bus;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").lat(37.500).lng(127.000).build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private Student dropoffStudent(Long id) {
        Student student = Student.builder().tenant(tenant(TENANT_ID)).name("학생" + id).build();
        ReflectionTestUtils.setField(student, "id", id);
        student.updateDropoff("하차지" + id, 37.500 + id / 1000.0, 127.000 + id / 1000.0);
        return student;
    }

    private AuthUser admin() {
        return new AuthUser(100L, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }
}
