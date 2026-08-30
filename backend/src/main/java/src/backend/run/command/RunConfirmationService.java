package src.backend.run.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 배치의 오케스트레이터(RTE-08, Phase 7) — 회차 1건을 idle → confirmed 로 전이시키는 전체
 * 흐름(읽기 → 노선 계산 → 저장 → 이벤트 발행)을 잇는다({@code RunConfirmationScheduler} 가 회차마다
 * {@link #confirmOne} 을 부른다).
 *
 * <p><b>이 클래스는 {@code @Transactional} 이 아니다.</b> {@link RouteComputationPipeline#compute}
 * 는 외부 지도 API 를 호출하는데, 그 호출을 트랜잭션 안에 넣으면 공급자가 느린 만큼 DB 커넥션을 붙든
 * 채 대기한다(그 클래스 자신의 javadoc 이 명시한 설계). 그래서 "읽기 → 계산" 은 여기서 트랜잭션 밖에
 * 두고, "확정 표시(idle → confirmed) + 4종 산출물 저장 + 이벤트 발행" 만 별도 빈
 * {@link RunConfirmationPersistence} 의 짧은 트랜잭션에 맡긴다 — 같은 클래스 안의 메서드 호출로
 * 두면 self-invocation 이 프록시를 우회해 {@code @Transactional} 이 적용되지 않는다(Spring AOP 의
 * 알려진 함정).
 *
 * <p>도중에 어디서 실패하든(학원 좌표 미등록·노선 미편성·계산 실패·저장 실패) 이 회차만 실패로
 * 끝난다 — 스케줄러가 회차별로 이 메서드를 개별 호출하고 예외를 그 자리에서 잡으므로(목표 4), 다른
 * 회차의 확정에는 영향을 주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RunConfirmationService {

    /**
     * 지도 API 1회 요청의 상한 — 확정 배치는 사용자가 대기하지 않으므로 온디맨드보다 길게 둔다
     * (ARCHITECTURE §8.3 · {@link CallerPolicy#BATCH} javadoc). 15초는 이 Phase 가 처음 도입하는
     * 값이라 참조할 기존 상수가 없다 — 재시도·서킷은 {@code resilience4j.*.instances.mapRoute} 설정이
     * 맡고, 이 값은 그 설정과 별개로 <b>1회 호출</b>이 무한정 배치 워커 슬롯을 붙들지 않게 막는
     * 상한이다.
     */
    private static final Duration MAP_TIMEOUT = Duration.ofSeconds(15);

    private final RunRepository runRepository;

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final RouteComputationPipeline pipeline;

    private final RunConfirmationPersistence persistence;

    private final RunConfirmationMetrics metrics;

    private final Clock clock;

    /**
     * 회차 1건을 확정한다 — 이미 취소됐거나 존재하지 않으면 조용히 건너뛴다(취소·삭제와 경합해도
     * 예외로 배치를 막지 않는다).
     *
     * @throws BusinessException 학원 좌표 미등록({@code ACADEMY_COORDINATES_MISSING}, 목표 5) ·
     *                            대응 고정 노선 미편성({@code ROUTE_NOT_CONFIGURED_FOR_RUN}) 일 때
     */
    public void confirmOne(Long runId) {
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null || run.isCanceled()) {
            return;
        }

        Academy academy = academyRepository.findById(run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            // Ruling 190: 좌표 미등록 학원을 다른 기준점으로 대체하지 않는다 — 이 회차만 실패시킨다.
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }

        Weekday weekday = weekdayOf(run.getServiceDate());
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));

        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                run.getAcademyId());
        if (routeStops.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }

        List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByIdInAndAcademyId(stopIds, run.getAcademyId()).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));

        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            // 정차 순서가 가리키는 승하차지가 학원 밖(또는 삭제됨) — findAllByIdInAndAcademyId 가
            // 빠뜨린 것과 같은 신호라 노선 미편성과 같은 오류로 답한다.
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }

        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        GeoPoint origin;
        GeoPoint destination;
        // Ruling 190 — 반대쪽 끝은 노선의 첫/마지막 정차지다: 등원은 첫 승차지→학원, 하원은 학원→마지막 하차지.
        if (run.getDirection() == Direction.TO_ACADEMY) {
            origin = new GeoPoint(firstStop.getLat(), firstStop.getLng());
            destination = academyPoint;
        } else {
            origin = academyPoint;
            destination = new GeoPoint(lastStop.getLat(), lastStop.getLng());
        }

        List<StudentDailyStop> dailyStops = weeklyAddressRepository.findDailyStopsByStopIds(run.getAcademyId(),
                stopIds, weekday, run.getDirection());
        List<Long> studentIds = dailyStops.stream().map(StudentDailyStop::getStudentId).distinct().toList();
        Map<Long, Long> studentStops = dailyStops.stream()
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));

        DailyRoster roster = DailyRoster.of(run.getAcademyId(), weekday, run.getDirection(), studentIds);
        ComputationPolicy policy = new ComputationPolicy(MAP_TIMEOUT, CallerPolicy.BATCH,
                RouteVersionSource.CONFIRM_BATCH);
        RouteComputationInput input = new RouteComputationInput(roster, origin, destination, List.of(),
                run.getDepartTime(), policy);

        // 외부 지도 API 를 부르는 계산은 트랜잭션 밖에서 돈다(클래스 javadoc) — 여기까지는 읽기뿐이다.
        RouteComputation computation = pipeline.compute(input);

        // run.getConfirmAt()(판정 시각)과 이 confirmedAt(완료 시각)이 동시에 확보되는 유일한
        // 자리라 배치 지연 지표(목표 8)를 여기서 계측한다.
        OffsetDateTime confirmedAt = OffsetDateTime.now(clock);

        boolean persisted = persistence.persist(run, computation, origin, destination, weekday, studentStops,
                confirmedAt);
        // persisted == false 는 동시 확정 경합에서 진 시도다(persist() javadoc) — 이 시도는 기록하지
        // 않는다. 승패 신호 없이 무조건 기록하면 표본 수가 확정 사건 수보다 부풀어, 이 지표가 가장
        // 필요한 순간(인스턴스 증설로 경합이 잦아질 때) 가장 부정확해진다. 실패해 위에서 예외로 빠진
        // 시도는 이 줄에 닿지 않아 기록되지 않고, 다음 틱 재시도가 성공할 때 run.getConfirmAt() 은
        // 그대로라 실패 구간까지 포함한 누적 지연으로 잡힌다.
        if (persisted) {
            metrics.recordLag(Duration.between(run.getConfirmAt(), confirmedAt));
        }
    }

    /**
     * 그 날짜의 요일 — {@code route.weekday} 의 값 공간으로 옮긴다({@code RunGenerationService.weekdayOf}
     * 와 같은 계산). {@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다.
     */
    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
