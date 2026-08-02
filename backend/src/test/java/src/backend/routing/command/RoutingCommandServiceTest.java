package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.roster.ActiveRosterReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Stop;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.dto.StudentOverride;
import src.backend.routing.dto.StudentOverride.OverrideAction;
import src.backend.routing.engine.RoutePlanComputer;
import src.backend.routing.engine.spec.BusAssigner;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;
import src.backend.routing.event.RoutePlanPublishedEvent;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * 노선 계획 생성의 <b>특성 테스트</b>(characterization test) — BE-4 리팩터링(계산부를 RoutePlanComputer 로 분리)
 * 전후로 동작이 달라지지 않았음을 고정한다. 경로 계산 포트 2개는 외부 호출 없는 결정적 Fake 로 대체해
 * "최적화 품질"이 아니라 <b>정차 배치·ETA 오프셋·검사 순서·좌표 우선순위</b>를 검증한다.
 * <p>뒤쪽 {@code applySimulation_*} 5건은 BE-5(시뮬레이션 채택) 몫으로, 정원 거부·배정 커밋·version+1 신규 행(I-5)을 고정한다.
 */
class RoutingCommandServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 10L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);
    /** P2(위치 변경)를 신청한 학부모 — republishForBus 의 actor 다(관리자가 아니다). */
    private static final Long PARENT_ID = 500L;

    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ActiveRosterReader activeRosterReader = mock(ActiveRosterReader.class);
    private final BusAssigner busAssigner = mock(BusAssigner.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    /** 삽입 순서를 그대로 돌려주는 결정적 엔진 — 최적화 품질이 아니라 배치·오프셋을 검증한다. */
    private final RouteEngine routeEngine = (depot, points) -> new ArrayList<>(points.keySet());
    private final FakeMapRouteClient mapRouteClient = new FakeMapRouteClient();
    private final RoutePlanComputer computer = new RoutePlanComputer(routeEngine, mapRouteClient, 7);

    /** 시뮬레이션은 mock 이 아니라 실제 구현을 쓴다 — 채택(apply)이 "서버 재계산" 결과를 저장하는지가 검증 대상이라서. */
    private final RoutePlanSimulationService simulationService = new RoutePlanSimulationService(
            routePlanRepository, busRepository, studentRepository, activeRosterReader, computer);

    private final RoutingCommandService service = new RoutingCommandService(
            routePlanRepository, busRepository, studentRepository, activeRosterReader,
            busAssigner, eventPublisher, computer, simulationService);

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

    // ── BE-5: 시뮬레이션 채택(applySimulation) ──

    @Test
    void applySimulation_addOverride_commitsAssignedBusOnStudent() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001));
        Student added = dropoffStudent(201L, 37.505, 127.005);
        given(studentRepository.findById(201L)).willReturn(Optional.of(added));

        service.applySimulation(admin(), simulateRequest(RouteDirection.DROPOFF,
                new StudentOverride(201L, OverrideAction.ADD, 37.505, 127.005)));

        // 채택은 시뮬레이션과 달리 배정을 실제로 커밋한다
        assertThat(added.getAssignedBus()).isNotNull();
        assertThat(added.getAssignedBus().getId()).isEqualTo(BUS_ID);
    }

    /**
     * I-9 회귀 — 채택 경로가 뚫리면 피해가 가장 크다. commitOverrides 가 assignBus 로 퇴원 학생을
     * 버스에 되돌리고, 이어지는 persistPlan 이 그 학생을 PUBLISHED 노선의 정차로 저장한다.
     * 그러면 DriveSessionCommandService 가 plan.getStops() 를 순회하며 퇴원생 보호자에게
     * 근접·미승차 알림까지 보낸다.
     */
    @Test
    void applySimulation_addOverrideOnInactiveStudent_throwsInvalidInputAndDoesNotAssignBus() {
        givenBus(bus(25, tenant(37.500, 127.000)));
        givenRoster(dropoffStudent(101L, 37.501, 127.001));
        Student inactive = dropoffStudent(201L, 37.505, 127.005);
        inactive.deactivate();
        given(studentRepository.findById(201L)).willReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.applySimulation(admin(), simulateRequest(RouteDirection.DROPOFF,
                new StudentOverride(201L, OverrideAction.ADD, 37.505, 127.005))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(inactive.getAssignedBus()).isNull();          // 배정이 커밋되지 않았다
        verify(routePlanRepository, never()).save(any());        // 노선도 저장되지 않았다
    }

    @Test
    void applySimulation_pickupMoveOverride_commitsPickupCoordinates() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        Student moved = pickupStudent(101L, 37.400, 127.400);
        givenRoster(moved);
        given(studentRepository.findById(101L)).willReturn(Optional.of(moved));

        service.applySimulation(admin(), simulateRequest(RouteDirection.PICKUP,
                new StudentOverride(101L, OverrideAction.MOVE, 37.520, 127.520)));

        // D-K: 등원 좌표는 학생 자체 컬럼에 저장된다
        assertThat(moved.getPickupLat()).isEqualTo(37.520);
        assertThat(moved.getPickupLng()).isEqualTo(127.520);
        // 공유 Stop 은 절대 건드리지 않는다 — 같은 정류장의 다른 학생이 함께 움직이면 안 된다
        assertThat(moved.getBoardingStop().getLat()).isEqualTo(37.400);
        assertThat(moved.getBoardingStop().getLng()).isEqualTo(127.400);
    }

    @Test
    void applySimulation_overSeatCapacity_throwsInvalidInput() {
        Bus bus = bus(1, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));

        // 시뮬레이션(보여주기)은 정원 초과를 허용하지만 저장 경로는 거부한다
        assertThatThrownBy(() -> service.applySimulation(admin(), simulateRequest(RouteDirection.DROPOFF)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void applySimulation_savesNewVersionRow() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));
        givenLatestPlan(existingPlan(3, RoutePlanStatus.PUBLISHED));

        RoutePlanResponse response = service.applySimulation(admin(), simulateRequest(RouteDirection.DROPOFF));

        assertThat(response.version()).isEqualTo(4);
        assertThat(response.status()).isEqualTo(RoutePlanStatus.PUBLISHED);   // 승인 배포까지 이어서 수행한다
    }

    @Test
    void applySimulation_doesNotMutateExistingPlan() {
        Bus bus = bus(25, tenant(37.500, 127.000));
        givenBus(bus);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));
        RoutePlan baseline = existingPlan(3, RoutePlanStatus.PUBLISHED);
        givenLatestPlan(baseline);

        service.applySimulation(admin(), simulateRequest(RouteDirection.DROPOFF));

        // I-5: 기존 행은 한 글자도 바뀌지 않는다
        assertThat(baseline.getVersion()).isEqualTo(3);
        assertThat(baseline.getStatus()).isEqualTo(RoutePlanStatus.PUBLISHED);
        assertThat(baseline.getStops()).hasSize(2);

        ArgumentCaptor<RoutePlan> savedPlan = ArgumentCaptor.forClass(RoutePlan.class);
        verify(routePlanRepository, times(1)).save(savedPlan.capture());
        assertThat(savedPlan.getValue()).isNotSameAs(baseline);
    }

    // ── BE-10: P2 자동 적용(republishForBus) ──

    /**
     * 학부모 위치 변경(P2)이 동기로 기대는 계약을 고정한다 — 반환값이 그대로
     * {@code LocationChangeRequest.appliedPlanId} 와 응답 {@code appliedPlanId} 가 되므로,
     * "저장된 새 계획의 id 를 돌려준다"가 깨지면 학부모 앱에 보이는 값이 바뀐다(reference.md §6 예외).
     */
    @Test
    void republishForBus_returnsIdOfNewlyPublishedVersion() {
        givenBus(bus(25, tenant(37.500, 127.000)));
        givenSaveAssignsId(1234L);
        givenRoster(dropoffStudent(101L, 37.501, 127.001), dropoffStudent(102L, 37.502, 127.002));
        givenLatestPlan(existingPlan(3, RoutePlanStatus.PUBLISHED));

        Long planId = service.republishForBus(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, PARENT_ID);

        assertThat(planId).isEqualTo(1234L);
        ArgumentCaptor<RoutePlan> saved = ArgumentCaptor.forClass(RoutePlan.class);
        verify(routePlanRepository).save(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(4);                       // I-5: 새 행
        // D-H: 관리자 승인 단계가 없다 — RECOMMENDED 에서 멈추지 않고 PUBLISHED 까지 간다.
        // 여기서 멈추면 기사 조회 API(PUBLISHED 만 노출)에 새 노선이 안 보여 "반영됐다"는 응답이 거짓이 된다.
        assertThat(saved.getValue().getStatus()).isEqualTo(RoutePlanStatus.PUBLISHED);
        assertThat(saved.getValue().getApprovedBy()).isEqualTo(PARENT_ID);            // 촉발한 학부모가 남는다
        assertThat(saved.getValue().getPublishedBy()).isEqualTo(PARENT_ID);
        verify(eventPublisher).publishEvent(any(RoutePlanPublishedEvent.class));      // F3: 기사 알림
    }

    /** 재계산 명단은 {@link ActiveRosterReader} 가 정한다 — 승인 결석자는 새 노선의 정차에서 빠진다. */
    @Test
    void republishForBus_planStopsFollowActiveRoster() {
        givenBus(bus(25, tenant(37.500, 127.000)));
        givenSaveAssignsId(1234L);
        givenRoster(dropoffStudent(101L, 37.501, 127.001));   // 102 는 결석 승인돼 명단에서 빠진 상태
        givenLatestPlan(existingPlan(3, RoutePlanStatus.PUBLISHED));

        service.republishForBus(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, PARENT_ID);

        ArgumentCaptor<RoutePlan> saved = ArgumentCaptor.forClass(RoutePlan.class);
        verify(routePlanRepository).save(saved.capture());
        assertThat(saved.getValue().getStops()).extracting(RoutePlanStop::getStudentId).containsExactly(101L);
    }

    /**
     * 로스터 0명이면 예외로 끝난다 — 조용히 성공하지 않는다는 것이 중요하다.
     * 호출자({@code LocationChangeCommandService})가 이 예외로 트랜잭션을 롤백해
     * "좌표는 바뀌었는데 노선은 옛날 것" 상태를 만들지 않는다.
     */
    @Test
    void republishForBus_emptyRoster_throwsInvalidInputAndSavesNothing() {
        givenBus(bus(25, tenant(37.500, 127.000)));
        givenRoster();

        assertThatThrownBy(() -> service.republishForBus(BUS_ID, RouteDirection.DROPOFF, SERVICE_DATE, PARENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(routePlanRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(RoutePlanPublishedEvent.class));
    }

    // ── fixtures ──

    private void givenBus(Bus bus) {
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        given(routePlanRepository.save(any(RoutePlan.class))).willAnswer(inv -> inv.getArgument(0));
    }

    /** 저장 시 id 를 채워 돌려준다 — republishForBus 의 반환값(appliedPlanId)이 이 id 다. */
    private void givenSaveAssignsId(Long planId) {
        given(routePlanRepository.save(any(RoutePlan.class))).willAnswer(inv -> {
            RoutePlan plan = inv.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", planId);
            return plan;
        });
    }

    private void givenRoster(Student... students) {
        given(activeRosterReader.forBus(BUS_ID, SERVICE_DATE)).willReturn(List.of(students));
    }

    private void givenLatestPlan(RoutePlan plan) {
        given(routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(BUS_ID, plan.getDirection()))
                .willReturn(Optional.of(plan));
    }

    private GenerateRoutePlanRequest request(RouteDirection direction) {
        return new GenerateRoutePlanRequest(BUS_ID, direction, SERVICE_DATE);
    }

    private SimulateRoutePlanRequest simulateRequest(RouteDirection direction, StudentOverride... overrides) {
        return new SimulateRoutePlanRequest(BUS_ID, direction, SERVICE_DATE, List.of(overrides));
    }

    /** baseline 역할의 저장된 최신 계획 — 정차 2개·4000m·240s. */
    private RoutePlan existingPlan(int version, RoutePlanStatus status) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(BUS_ID).direction(RouteDirection.DROPOFF).status(status)
                .version(version).serviceDate(SERVICE_DATE).polyline("[[37.5,127.0]]")
                .totalDistanceM(4000.0).totalDurationS(240.0).build();
        ReflectionTestUtils.setField(plan, "id", 900L);
        plan.addStop(101L, 37.501, 127.001, 60L);
        plan.addStop(102L, 37.502, 127.002, 120L);
        return plan;
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
