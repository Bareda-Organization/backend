package src.backend.routing.command;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteOptimizeRequest;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.query.RouteDetailAssembler;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.student.entity.Stop;

/**
 * 고정 노선의 정차 순서를 전략 포트로 다시 매긴다(RTE-09, API_SPEC §5.9 · Ruling 180).
 *
 * <p>편성 CRUD({@link RouteCommandService})와 가른 이유는 <b>바뀌는 계기가 다르기</b> 때문이다 —
 * 이쪽은 최적화 기준(거리·시간·정원 가중치, PRD §10.1 G)과 엔진 계약이 정해질 때, 저쪽은 편성의
 * 유일성 조합이 바뀔 때다. 가중치가 확정되면 이 클래스만 흔들린다.
 *
 * <p><b>부르는 것은 {@code POST /staff/routes/{id}/optimize} 하나뿐이다.</b> 편성·수정에 자동
 * 트리거를 달지 않는 것이 Ruling 180 의 요지다 — 기준이 정해지기 전에 재배열이 조용히 돌면 관리자가
 * 손으로 정한 차례가 이유 없이 뒤집힌다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RouteOptimizeService {

    /**
     * 고정 노선에는 탑승 인원을 알 수단이 부재하다 — 명단은 회차(그날의 운행)에 매달리고 편성은 학기
     * 단위 원본이라, 어느 날의 인원인지를 정할 수 있는 자리가 여기에 없다.
     *
     * <p>{@code OrderableStop.riderCount} 가 정원·체류시간 판정의 입력이므로 값을 지어내지 않고
     * 0 으로 둔다 — 지어낸 인원으로 순서를 정하면 그 값이 어디서 왔는지 사후에 설명할 수단이 부재하다.
     * 인원을 반영한 최적화는 회차 단위 계산(Phase 7)의 몫이다.
     */
    private static final int RIDER_COUNT_UNKNOWN = 0;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final RouteStopArranger routeStopArranger;

    private final RouteDetailAssembler routeDetailAssembler;

    private final RouteEngine routeEngine;

    /**
     * 지금 편성돼 있는 정차지를 엔진이 낸 차례로 다시 매긴다 — 대상이 다른 학원이면
     * {@code 404 ROUTE_NOT_FOUND} 다.
     *
     * <p>{@code fixedStops} 는 비운다 — 경유 지점(RTE-10)은 회차에 매달리는 것이라
     * ({@code waypoint.run_id}) 학기 단위 편성에는 그 자리가 부재하다. 경유 지점 지정은 Phase 8 이다.
     *
     * <p>정차지가 없으면 엔진이 빈 순서를 내고 갈아 끼울 것도 없다 — 오류가 아니다. 칸만 잡아 둔
     * 편성에 최적화를 부르는 것은 조작 실수이지 사고가 아니라, 거부하면 화면이 이유를 설명할 것이
     * 부재하다.
     */
    public RouteDetailResponse optimize(AuthUser requester, Long routeId, RouteOptimizeRequest request) {
        Route route = routeRepository.findByIdAndAcademyId(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        List<Long> currentOrder = routeStopRepository
                .findAllOrderedByRouteIdAndAcademyId(routeId, requester.academyId()).stream()
                .map(RouteStop::getStopId)
                .toList();
        Map<Long, Stop> stops = routeStopArranger.resolve(requester.academyId(), currentOrder);

        RouteOrderInput input = new RouteOrderInput(request.origin().toGeoPoint(),
                request.destination().toGeoPoint(), orderableStopsOf(currentOrder, stops), List.of(),
                route.getDirection());
        routeStopArranger.replace(routeId, requester.academyId(),
                routeEngine.order(input).sequence().stream().map(OrderedStop::stopId).toList());
        return routeDetailAssembler.assemble(route);
    }

    private List<OrderableStop> orderableStopsOf(List<Long> stopIds, Map<Long, Stop> stops) {
        return stopIds.stream()
                .map(stops::get)
                .map(stop -> new OrderableStop(stop.getId(),
                        new GeoPoint(stop.getLat(), stop.getLng()), RIDER_COUNT_UNKNOWN))
                .toList();
    }
}
