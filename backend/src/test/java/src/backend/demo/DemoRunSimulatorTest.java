package src.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
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

    private final DemoRunSimulator simulator = new DemoRunSimulator(runRepository, assignmentRepository,
            managerRepository, mock(ConfirmedRouteRepository.class), mock(RouteVersionRepository.class),
            runStartCommandService, mock(RunPositionCommandService.class), CLOCK);

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

    /** 학원 1 은 계약 검사와 함께 쓰는 학원이라 데모 선단 버스만 출발시킨다(2026-09-20 실측 사고). */
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
