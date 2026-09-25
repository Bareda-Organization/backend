package src.backend.demo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.location.command.RunPositionCommandService;
import src.backend.location.dto.RunPositionRequest;
import src.backend.manager.entity.Assignment;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.command.RunStartCommandService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 데모용 운행 시뮬레이터 — <b>{@code local} 프로파일에서만</b> 돈다(2026-09-20 사용자 요청).
 *
 * <p>로컬에서 관리자 화면을 열면 버스가 실제로 움직이는 것이 보여야 한다. 위치는 기사 단말이
 * 2초마다 올리는 값이고(§4.12) 서버는 그 값을 2분만 유효한 것으로 보므로, 아무도 올리지 않는
 * 로컬에서는 지도가 늘 비어 있다. 이 컴포넌트가 기사 단말 자리를 대신한다.
 *
 * <p><b>정식 서비스를 그대로 부른다</b> — 운행 시작은 {@link RunStartCommandService}, 위치는
 * {@link RunPositionCommandService}. 상태 전이·인가·이벤트 발행(WebSocket 방송, 근접 알림)이
 * 실제 운행과 같은 경로로 돌게 하려는 것이다. 리포지토리로 상태를 직접 바꾸면 화면에는 버스가
 * 보이지만 그 뒤에 달린 동작들은 하나도 안 돌아, 데모가 실제와 다른 것을 보여 주게 된다.
 *
 * <p>좌표는 확정 노선의 {@code road_path}(네이버에서 받은 실제 도로 경로)를 순서대로 따라간다 —
 * 직선으로 움직이면 한강 위를 지나간다. 끝에 닿으면 처음으로 돌아가 계속 돈다(데모라서 종료가
 * 목적이 아니다).
 *
 * <p>⚠ 운영에 절대 실리면 안 된다. {@code @Profile("local")} 하나에 기대지 말고, 이 클래스가
 * {@code demo} 패키지에 홀로 있는 것도 같은 뜻이다 — 도메인 패키지에 섞어 두면 다음 사람이
 * 운영 코드로 오인한다.
 */
@Slf4j
@Profile("local")
@Component
@RequiredArgsConstructor
public class DemoRunSimulator {

    /** 노선 한 바퀴를 도는 데 쓸 시간. 짧으면 순간이동처럼 보이고 길면 멈춘 것처럼 보인다. */
    private static final int LAP_MINUTES = 12;

    private static final long TICK_MS = 2000L;

    /** 회차별로 지금 road_path 의 몇 번째 좌표에 있는지. 재기동하면 처음으로 돌아간다(데모라 무방). */
    private final Map<Long, Integer> cursorByRun = new ConcurrentHashMap<>();

    private final RunRepository runRepository;

    private final AssignmentRepository assignmentRepository;

    private final ManagerRepository managerRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStartCommandService runStartCommandService;

    private final RunPositionCommandService runPositionCommandService;

    private final Clock clock;

    @Value("${app.demo.academy-id:1}")
    private long demoAcademyId;

    @Value("${app.demo.enabled:true}")
    private boolean enabled;

    /**
     * 시뮬레이터가 <b>출발시켜도 되는</b> 버스. 기본값은 V13 이 만든 데모 선단(3·4·5호차)이다.
     *
     * <p>범위를 좁히는 이유 — 좁히기 전에는 시작 창에 들어온 확정 회차를 전부 출발시켰고, 그 바람에
     * 데모와 무관한 <b>1호차 하원 회차까지 운행 중으로 바뀌었다</b>(2026-09-20 실측). 그 회차는 웹
     * 계약 검사가 "확정 상태" 로 붙들고 쓰는 대상이라(구간변경 승인 미리보기), 데모를 한 번 띄운
     * 것만으로 검사가 깨지는 상태가 된다. <b>위치 송신은 좁히지 않는다</b> — 이미 운행 중인 회차에
     * 좌표를 더 넣는 것은 상태를 바꾸지 않아 옆으로 새지 않는다.
     */
    @Value("${app.demo.bus-ids:10,11,12}")
    private List<Long> demoBusIds;

    /**
     * 통째로 데모인 학원 — V14 가 만든 10곳(2026-09-23). 계약 검사가 붙드는 행이 부재해 버스를 가리지
     * 않고 출발시킨다. 위의 버스 목록은 학원 {@link #demoAcademyId} 안에서만 쓰는 좁힘이다.
     */
    @Value("${app.demo.academy-ids:11,12,13,14,15,16,17,18,19,20}")
    private List<Long> demoAcademyIds;

    // 첫 실행을 늦추는 값은 설정 자리표시자로 둔다 — 테스트 JVM 에서 이 시뮬레이터가 배경으로 돌면
    // 검사가 만든 회차를 먼저 집어 출발 판정이 실행 순서에 따라 갈린다(build.gradle 의 test 블록이
    // 하루로 미룬다). 로컬 실행 기본값은 그대로 15초다.
    @Scheduled(fixedDelay = TICK_MS, initialDelayString = "${app.demo.initial-delay-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        startDueRuns();
        advanceMovingRuns();
    }

    /**
     * 확정된 회차 중 시작 창(예정 시각 ±3분)에 들어온 것을 출발시킨다.
     *
     * <p>창을 직접 계산하지 않고 서비스가 던지는 것을 받아 넘긴다 — 창 판정 규칙이 한 곳에만
     * 있어야 데모와 실제가 갈라지지 않는다.
     *
     * <p>이미 운행 중인 버스의 다음 회차는 세워 둔다 — 데모 운행은 끝나지 않고 계속 돌아서, 출발시키면
     * 같은 버스가 지도에 두 대 뜬다. 단 <b>오늘</b> 운행 중인 회차만 센다(BR-132) — 운행이 끝나지 않으니 자정을
     * 넘기면 전날 회차가 남고, 그것까지 세면 그 버스의 오늘 회차는 영영 출발하지 않는다.
     */
    private void startDueRuns() {
        for (Long academyId : academies()) {
            LocalDate today = LocalDate.now(clock);
            Set<Long> busyBusIds = new HashSet<>();
            runRepository.findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId, RunStatus.MOVING).stream()
                    .filter(run -> today.equals(run.getServiceDate()))
                    .forEach(run -> busyBusIds.add(run.getBusId()));
            for (Run run : runRepository.findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId,
                    RunStatus.CONFIRMED)) {
                if (startIfDue(run, busyBusIds)) {
                    busyBusIds.add(run.getBusId());
                }
            }
        }
    }

    /** 데모 대상이고 그 버스가 아직 안 바쁘면 이 회차를 출발시킨다 — 성공하면 {@code true}(§20.2, BR-101). */
    private boolean startIfDue(Run run, Set<Long> busyBusIds) {
        if (!isDemo(run) || busyBusIds.contains(run.getBusId())) {
            return false;
        }
        return driverOf(run).map(driver -> {
            try {
                runStartCommandService.start(driver, run.getId());
                log.info("[데모] 회차 {} 운행 시작", run.getId());
                return true;
            } catch (RuntimeException e) {
                // 시작 창 밖(START_WINDOW_CLOSED)이 대부분이다 — 데모에서는 정상 상태라 조용히 넘긴다.
                log.debug("[데모] 회차 {} 시작 보류: {}", run.getId(), e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    private boolean isDemo(Run run) {
        return demoAcademyIds.contains(run.getAcademyId()) || demoBusIds.contains(run.getBusId());
    }

    private List<Long> academies() {
        return Stream.concat(Stream.of(demoAcademyId), demoAcademyIds.stream()).distinct().toList();
    }

    private void advanceMovingRuns() {
        List<Run> movingRuns = academies().stream()
                .flatMap(academyId -> runRepository
                        .findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId, RunStatus.MOVING).stream())
                .toList();
        for (Run run : movingRuns) {
            advanceOne(run);
        }
    }

    /** 회차 하나를 도로 경로 위 다음 지점으로 옮기고 그 위치를 실 단말과 같은 명령 경로로 보낸다(§20.2, BR-101). */
    private void advanceOne(Run run) {
        List<GeoPoint> path = roadPathOf(run.getId());
        if (path.size() < 2) {
            return;
        }
        int step = Math.max(1, path.size() / (int) (LAP_MINUTES * 60_000L / TICK_MS));
        int cursor = (cursorByRun.getOrDefault(run.getId(), 0) + step) % path.size();
        cursorByRun.put(run.getId(), cursor);
        GeoPoint point = path.get(cursor);
        driverOf(run).ifPresent(driver -> {
            try {
                runPositionCommandService.receive(driver, run.getId(),
                        new RunPositionRequest(point.lat(), point.lng(), OffsetDateTime.now(clock), null, null));
            } catch (RuntimeException e) {
                log.debug("[데모] 회차 {} 위치 송신 실패: {}", run.getId(), e.getMessage());
            }
        });
    }

    /** 확정 노선의 도로 경로. 아직 확정 전이거나 배포된 버전이 없으면 빈 목록이다. */
    private List<GeoPoint> roadPathOf(Long runId) {
        return confirmedRouteRepository.findById(runId)
                .map(ConfirmedRoute::getCurrentVersionId)
                .flatMap(routeVersionRepository::findById)
                .map(version -> version.getRoadPath() == null ? List.<GeoPoint>of() : version.getRoadPath())
                .orElseGet(List::of);
    }

    /** 그 회차에 배치된 기사를 요청 주체로 만든다 — 정식 서비스가 "배치된 기사인가" 를 계정으로 판정한다. */
    private java.util.Optional<AuthUser> driverOf(Run run) {
        return assignmentRepository.findByRunIdAndRole(run.getId(), ManagerRole.DRIVER)
                .map(Assignment::getManagerId)
                .flatMap(managerRepository::findById)
                .map(manager -> manager.getAccountId())
                .map(accountId -> new AuthUser(accountId, run.getAcademyId(), Role.DRIVER, AccountStatus.ACTIVE));
    }
}
