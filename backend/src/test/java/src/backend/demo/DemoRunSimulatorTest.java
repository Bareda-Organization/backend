package src.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.AuthUser;
import src.backend.location.command.RunPositionCommandService;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.global.location.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.command.RunStartCommandService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 데모 시뮬레이터가 <b>어느 회차를 출발시키는가</b>를 고정한다.
 *
 * <p>범위가 넓어지면 계약 검사가 붙드는 회차(학원 1 의 1·2호차)까지 운행 중으로 바뀌고, 좁아지면
 * 데모 학원 10곳(V14)의 버스가 서 있는다. 둘 다 화면을 봐야만 드러나는 형태라 여기서 먼저 막는다.
 */
class DemoRunSimulatorTest {

    private static final long MAIN_ACADEMY = 1L;

    private static final long DEMO_ACADEMY = 11L;

    /** 운영과 같은 서비스 시간대의 고정 시계 — 기계 시간대·자정 근처 실행에 결과가 갈리지 않는다(BR-265). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-04-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final RunRepository runRepository = mock(RunRepository.class);

    private final AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);

    private final ManagerRepository managerRepository = mock(ManagerRepository.class);

    private final RunStartCommandService runStartCommandService = mock(RunStartCommandService.class);

    private final ConfirmedRouteRepository confirmedRouteRepository = mock(ConfirmedRouteRepository.class);

    private final RouteVersionRepository routeVersionRepository = mock(RouteVersionRepository.class);

    private final RunPositionCommandService runPositionCommandService = mock(RunPositionCommandService.class);

    private final RunPositionStore runPositionStore = mock(RunPositionStore.class);

    private final DemoRunSimulator simulator = new DemoRunSimulator(runRepository, assignmentRepository,
            managerRepository, confirmedRouteRepository, routeVersionRepository,
            runStartCommandService, runPositionCommandService, runPositionStore, CLOCK);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(simulator, "enabled", true);
        ReflectionTestUtils.setField(simulator, "demoAcademyId", MAIN_ACADEMY);
        ReflectionTestUtils.setField(simulator, "demoBusIds", List.of(10L));
        ReflectionTestUtils.setField(simulator, "demoAcademyIds", List.of(DEMO_ACADEMY));
    }

    /** 데모 학원은 통째로 데모라 버스 목록에 없어도 출발시키고, 요청 주체는 <b>그 학원의</b> 기사다. */
    @Test
    void 데모_학원의_확정_회차는_버스를_가리지_않고_그_학원_기사로_출발시킨다() {
        회차들(DEMO_ACADEMY, RunStatus.CONFIRMED, 회차(1000L, 1000L, DEMO_ACADEMY));

        simulator.tick();

        ArgumentCaptor<AuthUser> driver = ArgumentCaptor.forClass(AuthUser.class);
        verify(runStartCommandService).start(driver.capture(), eq(1000L));
        assertThat(driver.getValue().academyId()).isEqualTo(DEMO_ACADEMY);
    }

    /** 한 버스가 두 회차를 동시에 달리면 지도에 같은 버스가 두 대 뜬다 — 앞 회차가 도는 동안은 세워 둔다. */
    @Test
    void 같은_버스가_이미_운행_중이면_다음_회차는_출발시키지_않는다() {
        회차들(DEMO_ACADEMY, RunStatus.MOVING, 회차(1000L, 1000L, DEMO_ACADEMY));
        회차들(DEMO_ACADEMY, RunStatus.CONFIRMED, 회차(1001L, 1000L, DEMO_ACADEMY));

        simulator.tick();

        verify(runStartCommandService, never()).start(any(), eq(1001L));
    }

    /** 학원 1 은 계약 검사와 함께 쓰는 학원이라 데모 선단 버스만 출발시킨다. */
    @Test
    void 학원_1_은_데모_선단_버스만_출발시킨다() {
        회차들(MAIN_ACADEMY, RunStatus.CONFIRMED, 회차(100L, 10L, MAIN_ACADEMY), 회차(6L, 1L, MAIN_ACADEMY));

        simulator.tick();

        verify(runStartCommandService).start(any(), eq(100L));
        verify(runStartCommandService, never()).start(any(), eq(6L));
    }

    /**
     * 전날 운행 중으로 남은 회차가 그 버스의 오늘 회차 출발을 막지 않는다(BR-132) — 시뮬레이터는 운행을 끝내지 않아
     * 자정을 넘기면 전날 {@code moving} 회차가 남는다. 날짜를 보지 않으면 그 버스는 영영 출발하지 않는다.
     */
    @Test
    void 전날_운행_중으로_남은_회차는_그_버스의_오늘_회차를_막지_않는다() {
        Run yesterday = 회차(1000L, 1000L, DEMO_ACADEMY);
        given(yesterday.getServiceDate()).willReturn(LocalDate.now(CLOCK).minusDays(1));
        회차들(DEMO_ACADEMY, RunStatus.MOVING, yesterday);
        회차들(DEMO_ACADEMY, RunStatus.CONFIRMED, 회차(1001L, 1000L, DEMO_ACADEMY));

        simulator.tick();

        verify(runStartCommandService).start(any(), eq(1001L));
    }

    /**
     * 전날 이전 회차는 전진·출발 시도 모두 하지 않는다(BR-217) — 시뮬레이터가 운행을 끝내지 않아 켜 둔 일수만큼 옛 회차가
     * 쌓이고, 날짜를 보지 않으면 틱(2초)마다 그 수만큼 경로 조회·위치 송신을 되풀이한다.
     */
    @Test
    void 전날_이전_회차는_전진도_출발도_시키지_않는다() {
        Run movingToday = 회차(1000L, 1000L, DEMO_ACADEMY);
        Run movingYesterday = 회차(1001L, 1001L, DEMO_ACADEMY);
        given(movingYesterday.getServiceDate()).willReturn(LocalDate.now(CLOCK).minusDays(1));
        회차들(DEMO_ACADEMY, RunStatus.MOVING, movingToday, movingYesterday);
        Run confirmedYesterday = 회차(1002L, 1002L, DEMO_ACADEMY);
        given(confirmedYesterday.getServiceDate()).willReturn(LocalDate.now(CLOCK).minusDays(1));
        회차들(DEMO_ACADEMY, RunStatus.CONFIRMED, confirmedYesterday);

        simulator.tick();

        verify(confirmedRouteRepository).findById(1000L);
        verify(confirmedRouteRepository, never()).findById(1001L);
        verify(runStartCommandService, never()).start(any(), eq(1002L));
    }

    /**
     * 기사 단말이 위치를 보내는 회차에는 가짜 위치를 더 넣지 않는다 — 학원 A 회차를 매니저 앱으로 출발시키면 기사 폰의
     * 실제 위치와 시뮬레이터의 노선 위 위치가 2초마다 번갈아 들어가 학부모 지도의 버스가 오가고 근접 · 출발 판정이 기사
     * 조작과 무관하게 일어난다.
     */
    @Test
    void 기사_단말이_위치를_보낸_회차는_더_전진시키지_않는다() {
        회차들(MAIN_ACADEMY, RunStatus.MOVING, 회차(7L, 3L, MAIN_ACADEMY));
        도로_경로(7L);

        simulator.tick();   // 첫 송신 — 아직 이 회차의 위치를 보낸 다른 주체가 없다
        given(runPositionStore.find(7L)).willReturn(Optional.of(new RunPositionRedisValue(BigDecimal.valueOf(37.0),
                BigDecimal.valueOf(127.0), OffsetDateTime.now(CLOCK).plusSeconds(1), OffsetDateTime.now(CLOCK), null)));
        simulator.tick();   // 최신 위치가 시뮬레이터가 보낸 것이 아니다 = 기사 단말이 보내는 중
        simulator.tick();

        verify(runPositionCommandService, times(1)).receive(any(), eq(7L), any());
    }

    /** 다른 주체의 위치가 없으면 지금처럼 틱마다 보낸다 — 이미 운행 중인 시드 회차의 관제 화면용 움직임을 지킨다. */
    @Test
    void 다른_주체의_위치가_없으면_틱마다_전진시킨다() {
        회차들(MAIN_ACADEMY, RunStatus.MOVING, 회차(8L, 3L, MAIN_ACADEMY));
        도로_경로(8L);

        simulator.tick();
        simulator.tick();

        verify(runPositionCommandService, times(2)).receive(any(), eq(8L), any());
    }

    private void 도로_경로(long runId) {
        ConfirmedRoute route = mock(ConfirmedRoute.class);
        given(route.getCurrentVersionId()).willReturn(runId + 900L);
        given(confirmedRouteRepository.findById(runId)).willReturn(Optional.of(route));
        RouteVersion version = mock(RouteVersion.class);
        given(version.getRoadPath()).willReturn(List.of(점(37.50, 126.76), 점(37.51, 126.77), 점(37.52, 126.78)));
        given(routeVersionRepository.findById(runId + 900L)).willReturn(Optional.of(version));
    }

    private static GeoPoint 점(double lat, double lng) {
        return new GeoPoint(BigDecimal.valueOf(lat), BigDecimal.valueOf(lng));
    }

    private void 회차들(long academyId, RunStatus status, Run... runs) {
        given(runRepository.findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId, status))
                .willReturn(List.of(runs));
    }

    /** 회차 하나와, 그 회차에 배치된 기사(계정 id = 회차 id + 50000)를 함께 세운다. */
    private Run 회차(long runId, long busId, long academyId) {
        Run run = mock(Run.class);
        given(run.getId()).willReturn(runId);
        given(run.getBusId()).willReturn(busId);
        given(run.getAcademyId()).willReturn(academyId);
        given(run.getServiceDate()).willReturn(LocalDate.now(CLOCK));

        Assignment assignment = mock(Assignment.class);
        given(assignment.getManagerId()).willReturn(runId);
        given(assignmentRepository.findByRunIdAndRole(runId, ManagerRole.DRIVER)).willReturn(Optional.of(assignment));
        Manager manager = mock(Manager.class);
        given(manager.getAccountId()).willReturn(runId + 50000L);
        given(managerRepository.findById(runId)).willReturn(Optional.of(manager));
        return run;
    }
}
