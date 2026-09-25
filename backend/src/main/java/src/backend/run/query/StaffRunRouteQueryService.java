package src.backend.run.query;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.StaffAssignmentAckView;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.domain.RunRouteEndpoints;
import src.backend.run.domain.RunWeekday;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.dto.RunRouteResponse.RouteStop;
import src.backend.run.dto.StaffRunRouteResponse;
import src.backend.run.dto.StaffRunRouteResponse.Ack;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 관계자용 확정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route}, RTE-02, F1 S3 목표
 * 11).
 *
 * <p>존재 판정과 학원 범위 판정을 분리한다({@code RosterQueryService#staffRoster} 와 같은 근거) —
 * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
 * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다.
 *
 * <p>{@code 409 RUN_NOT_CONFIRMED} 는 배포된 확정 노선(route_version)도 없고 <b>고정 노선으로 낼
 * 예정 경로도 없을 때</b>다(R20-A, Ruling 321) — 매니저용 {@link RunRouteQueryService#route} 는 같은
 * 상태에서 빈 200 을 돌려주지만(운행 중 단말의 일시적 공백을 허용), 관계자는 확정 노선을 "보는"
 * 용도라 둘 다 없으면 아직 볼 것이 없다는 뜻이다.
 *
 * <p><b>클래스에 트랜잭션을 걸지 않는다</b>(BR-046) — 예정 경로 계산이 외부 지도 API 를 부르므로, 그 안에
 * 있으면 지도가 느린 만큼 DB 커넥션을 쥔 채 기다린다({@code RouteComputationPipeline} 자바독과 같은 근거).
 * 조회는 저장소 호출마다 짧은 읽기 트랜잭션으로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class StaffRunRouteQueryService {

    /** 예정 경로 계산 — 관계자가 화면에서 대기 중이므로 서킷 개방 시 즉시 오류로 끝낸다(ON_DEMAND). */
    private static final Duration PLANNED_ROUTE_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRouteQueryService runRouteQueryService;

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final RouteComputationPipeline routeComputationPipeline;

    private final Clock clock;

    public StaffRunRouteResponse route(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());

        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return plannedRouteOf(requester, run)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));
        }
        // 방어적 조회 — currentVersionId 가 가리키는 route_version 행은 배포 절차상 항상 존재해야
        // 하지만(순환 FK 쌍이라 DB 제약으로는 못 막는다), 없으면 "볼 확정 노선이 없다"는 결론은
        // 같으므로 별도 오류 코드를 새로 만들지 않고 같은 409 로 묶는다.
        RouteVersion version = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));

        RunRouteResponse base = runRouteQueryService.buildFromVersion(run, currentVersionId);
        Ack ack = ackOf(run.getAcademyId(), runId);
        List<GeoPoint> roadPath = version.getRoadPath() == null ? List.of() : version.getRoadPath();

        return new StaffRunRouteResponse(base.stops(), base.currentStop(), base.nextStop(), base.skippedNotice(),
                version.getVersionNo(), version.getPublishedAt(), ack, roadPath, version.isFallbackUsed(), true);
    }

    /**
     * 확정 전(idle) 회차의 <b>예정 경로</b>(R20-A, Ruling 321) — 고정 노선(§7 규칙 12 교체 축과
     * 무관하게 {@code route}·{@code route_stop} 이 이미 갖고 있는 데이터)과 오늘 자 요일별 주소만으로
     * 계산한다. 강제 추가·버스 간 이동·승인된 경유지 재배치 같은 당일 변경(RTE-06·07, P-06)은 반영하지
     * 않는다 — 확정 배치가 그 순간의 명단으로 다시 계산하므로 이 값은 처음부터 "예정"일 뿐이고, 그
     * 근사를 위해 {@code confirmOne} 의 roster 조립 전부를 그대로 옮겨 오는 비용을 지지 않는다(과업
     * 지시서 판단 근거 — 예정 노선은 확정본과 다를 수 있음을 이미 계약으로 명시했다).
     *
     * <p>캐시하지 않는다 — 관리자만 쓰고 조회가 잦지 않다(사용자 확정, Ruling 321 정정).
     *
     * @return 고정 노선을 못 찾거나 학원에 좌표가 없으면 {@code Optional.empty()} — 호출부가 이 경우도
     *         {@code RUN_NOT_CONFIRMED} 로 묶어 "조용한 빈 값" 대신 명시적 오류로 알린다
     */
    private java.util.Optional<StaffRunRouteResponse> plannedRouteOf(AuthUser requester, Run run) {
        Academy academy = academyRepository.findById(run.getAcademyId()).orElse(null);
        if (academy == null || !academy.hasCoordinates()) {
            return java.util.Optional.empty();
        }

        Weekday weekday = RunWeekday.of(run.getServiceDate());
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .orElse(null);
        if (route == null) {
            return java.util.Optional.empty();
        }

        List<src.backend.routing.entity.RouteStop> routeStops = routeStopRepository
                .findAllOrderedByRouteIdAndAcademyId(route.getId(), run.getAcademyId());
        if (routeStops.isEmpty()) {
            return java.util.Optional.empty();
        }

        List<Long> stopIds = routeStops.stream().map(src.backend.routing.entity.RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByIdInAndAcademyId(stopIds, run.getAcademyId()).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            return java.util.Optional.empty();
        }

        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        // Ruling 190(confirmOne 과 같은 규칙) — RunRouteEndpoints 로 통합(BR-101).
        RunRouteEndpoints.Endpoints endpoints = RunRouteEndpoints.of(run.getDirection(), academyPoint,
                new GeoPoint(firstStop.getLat(), firstStop.getLng()), new GeoPoint(lastStop.getLat(), lastStop.getLng()));
        GeoPoint origin = endpoints.origin();
        GeoPoint destination = endpoints.destination();

        List<StudentDailyStop> dailyStops = weeklyAddressRepository.findDailyStopsByStopIds(run.getAcademyId(),
                stopIds, weekday, run.getDirection(), run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime());
        List<Long> studentIds = dailyStops.stream().map(StudentDailyStop::getStudentId).distinct().toList();
        Map<Long, Long> studentStops = dailyStops.stream()
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));

        DailyRoster roster = new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, Map.of());
        ComputationPolicy policy = new ComputationPolicy(PLANNED_ROUTE_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.APPROVAL, false);
        RouteComputationInput input = new RouteComputationInput(roster, origin, destination, List.of(),
                run.getDepartTime(), policy);
        RouteComputation computation = routeComputationPipeline.compute(input);

        Map<Long, Long> studentCountsByStopId = studentStops.values().stream()
                .collect(Collectors.groupingBy(stopId -> stopId, Collectors.counting()));
        List<RouteStop> stops = computation.stops().stream()
                .map(stop -> toRouteStop(stop, stopsById.get(stop.stopId()), studentCountsByStopId))
                .toList();

        return java.util.Optional.of(new StaffRunRouteResponse(stops, null, null, null, 0, null,
                new Ack(false, false), computation.roadPath(), computation.snapshot().fallbackUsed(), false));
    }

    private RouteStop toRouteStop(OrderedStop stop, Stop stopEntity, Map<Long, Long> studentCounts) {
        if (stopEntity == null) {
            return new RouteStop(null, stop.seq(), null, null, null, null, null, 0L, false);
        }
        long studentCount = studentCounts.getOrDefault(stopEntity.getId(), 0L);
        return new RouteStop(stopEntity.getId(), stop.seq(), stopEntity.getName(), stopEntity.getAddress(),
                stopEntity.getLat(), stopEntity.getLng(), null, studentCount, false);
    }

    /**
     * {@code Assignment.ackedRouteVersionId == 현재 확정 버전} — 대시보드가 쓰는 것과 같은 비교(§5.3,
     * {@code StaffDashboardQueryService#ackedOf} 참고).
     *
     * <p>R22 — 요청자의 {@code academyId} 가 아니라 <b>회차가 속한 학원</b>으로 찾는다. 메인 관리자는
     * {@code academyId} 가 {@code null} 이라(그 역할만 null 이 허용된다, {@link AuthUser}) 요청자 기준으로
     * 찾으면 일치하는 행이 없어 확인 현황이 늘 "둘 다 미확인" 으로 나온다 — 권한이 없어서가 아니라
     * 조회 조건이 안 맞아서인데, 빈 결과라 구별할 수단이 부재하다. 이 지점에 닿기 전에
     * {@code AcademyScope#assertAccessible} 이 이미 접근을 판정했으므로 회차의 학원을 그대로 쓰는 것이
     * 두 역할 모두에게 옳다.
     */
    private Ack ackOf(Long academyId, Long runId) {
        List<StaffAssignmentAckView> views = assignmentRepository
                .findAckViewsForStaffDashboard(academyId, List.of(runId));
        return new Ack(ackedOf(views, ManagerRole.DRIVER), ackedOf(views, ManagerRole.ESCORT));
    }

    private boolean ackedOf(List<StaffAssignmentAckView> views, ManagerRole role) {
        return views.stream().filter(view -> view.role() == role).findFirst()
                .map(StaffAssignmentAckView::acked).orElse(false);
    }
}
