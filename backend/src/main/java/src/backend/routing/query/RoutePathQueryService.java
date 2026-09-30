package src.backend.routing.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.dto.RoutePathResponse;
import src.backend.routing.dto.RouteStopResponse;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 고정 노선 편성의 도로 경로(API_SPEC §5.9 {@code GET /staff/routes/{id}/path} 신설, RTE-01) —
 * 정차 순서({@code route_stop.seq}) 그대로 이은 실제 도로 좌표열을 {@link MapRouteClient} 로 얻는다.
 *
 * <p>조회 서비스({@link RouteQueryService})와 별도 컴포넌트인 이유는 <b>이 클래스만 외부 포트를
 * 호출</b>하기 때문이다 — 상세·목록 조회에 이 책임을 얹으면 트랜잭션 안에서 도로 경로 호출이
 * 섞이는 자리가 하나 더 생긴다(SRP, {@code RouteDetailAssembler} 와 같은 분리 근거).
 *
 * <p><b>클래스에 트랜잭션을 열지 않는다</b>(BR-211) — 지도 호출이 느린 만큼 DB 커넥션을 쥔 채 기다리지 않도록, 저장소 조회는
 * 각자 짧은 읽기 트랜잭션으로 끝나고 지도 호출은 그 밖에서 일어난다({@code StaffRunRouteQueryService} 와 같은 형태, BR-046).
 */
@Service
@RequiredArgsConstructor
public class RoutePathQueryService {

    /** 관계자가 화면에서 대기 중이므로 서킷 개방 시 즉시 오류로 끝낸다(ON_DEMAND, {@code StaffRunRouteQueryService} 와 같은 값). */
    private static final Duration ROUTE_PATH_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final AcademyRepository academyRepository;

    private final MapRouteClient mapRouteClient;

    /** 편성 1건의 도로 경로 — 다른 학원의 편성을 지목하면 {@code 404 ROUTE_NOT_FOUND} 다. */
    public RoutePathResponse path(AuthUser requester, Long routeId) {
        Route route = routeRepository.findByIdAndAcademyId(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));

        List<RouteStop> ordered = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                route.getAcademyId());
        if (ordered.isEmpty()) {
            return new RoutePathResponse(List.of(), false, List.of());
        }

        List<RouteStopResponse> stops = stopResponsesOf(route, ordered);
        List<GeoPoint> points = pointsOf(route, stops);
        if (points.size() < 2) {
            return new RoutePathResponse(List.of(), false, stops);
        }

        RoadRoute roadRoute = mapRouteClient
                .route(new RoadRouteRequest(points, ROUTE_PATH_MAP_TIMEOUT, CallerPolicy.ON_DEMAND));
        return new RoutePathResponse(roadRoute.roadPath(), roadRoute.fallbackUsed(), stops);
    }

    /**
     * 정차 순번마다 승하차지 이름·좌표를 붙인다 — {@code RouteDetailAssembler.stopResponsesOf} 와
     * 같은 조회 방식이나, <b>참조하는 승하차지 행이 없으면 그 정차지를 목록에서 뺀다</b>. 데이터
     * 정합이 어긋난(예: 삭제된 승하차지를 여전히 가리키는 {@code route_stop}) 극히 드문 경우에도
     * {@code 500} 대신 나머지 정차지로 경로를 계속 그리기 위함이다.
     */
    private List<RouteStopResponse> stopResponsesOf(Route route, List<RouteStop> ordered) {
        Map<Long, Stop> stops = stopRepository.findAllByAcademyIdAndIdIn(route.getAcademyId(),
                        ordered.stream().map(RouteStop::getStopId).toList()).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));
        return ordered.stream()
                .filter(routeStop -> stops.containsKey(routeStop.getStopId()))
                .map(routeStop -> RouteStopResponse.of(routeStop, stops.get(routeStop.getStopId())))
                .toList();
    }

    /**
     * 방향별 기준점(Ruling 190, {@code StaffRunRouteQueryService.plannedRouteOf} 와 같은 규칙) —
     * 등원은 첫 승차지 → 학원, 하원은 학원 → 마지막 하차지.
     *
     * <p><b>판단 근거(과업 지시서가 정하라고 남긴 지점)</b> — 학원 좌표가 없으면(고 좌표 미등록
     * 학원 · Phase 7 이전 데이터) 학원 쪽 끝점을 <b>그냥 뺀다</b>. 이 엔드포인트가 다루는 것은
     * §5.19 확정 노선이 아니라 학기 단위 원본 편성이고, 그 화면의 질문은 "정차지끼리 어떤 차례로
     * 도는가"라 학원 기준점이 없어도 여전히 유효하다({@code plannedRouteOf} 가 학원 좌표 부재 시
     * 전체를 {@code Optional.empty()} 로 접는 것과 다르다 — 저쪽은 "예정 경로 자체가 성립하지
     * 않는다"는 판단이고, 이쪽은 편성 조회라 학원 기준점 없이도 부분 정보로 응답할 가치가 있다).
     */
    private List<GeoPoint> pointsOf(Route route, List<RouteStopResponse> stops) {
        List<GeoPoint> stopPoints = stops.stream().map(stop -> new GeoPoint(stop.lat(), stop.lng())).toList();
        Academy academy = academyRepository.findById(route.getAcademyId()).orElse(null);
        if (academy == null || !academy.hasCoordinates()) {
            return stopPoints;
        }
        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        List<GeoPoint> points = new ArrayList<>();
        if (route.getDirection() == Direction.TO_ACADEMY) {
            points.addAll(stopPoints);
            points.add(academyPoint);
        } else {
            points.add(academyPoint);
            points.addAll(stopPoints);
        }
        return points;
    }
}
