package src.backend.routing.command;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteOptimizeRequest;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteUpdateRequest;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.query.RouteDetailAssembler;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.student.entity.Stop;

/** 고정 노선 편성·수정·삭제·순서 최적화(RTE-01 · RTE-09, API_SPEC §5.9 · Ruling 180). */
@Service
@RequiredArgsConstructor
@Transactional
public class RouteCommandService {

    /**
     * 고정 노선에는 탑승 인원을 알 수단이 부재하다 — 명단은 회차(그날의 운행)에 매달리고 편성은 학기
     * 단위 원본이라, 어느 날의 인원인지를 정할 수 있는 자리가 여기에 없다.
     *
     * <p>{@code OrderableStop.riderCount} 가 정원·체류시간 판정의 입력이므로 값을 지어내지 않고
     * 0 으로 둔다 — 지어낸 인원으로 순서를 정하면 그 값이 어디서 왔는지 사후에 설명할 수단이 부재하다.
     * 인원을 반영한 최적화는 회차 단위 계산(Phase 7)의 몫이다.
     */
    private static final int RIDER_COUNT_UNKNOWN = 0;

    /**
     * 유일성 조합을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code route} 에 CHECK 가 둘 더 있기 때문이다
     * ({@code ck_route_weekday}·{@code ck_route_direction}). 제약을 가리지 않고
     * {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 값 도메인 위반까지 "이미 편성된
     * 차량·요일·방향입니다" 로 답해 원인을 감춘다({@code BusCommandService} 와 같은 형태).
     */
    private static final String ROUTE_SLOT_UNIQUE_CONSTRAINT = "uk_route_bus_weekday_direction";

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final BusRepository busRepository;

    private final RouteStopArranger routeStopArranger;

    private final RouteDetailAssembler routeDetailAssembler;

    private final RouteEngine routeEngine;

    /**
     * 고정 노선을 편성한다(§5.9) — 소속 학원은 토큰에서만 온다(§1.5).
     *
     * <p>차량을 <b>먼저</b> 학원으로 좁혀 확인한다 — 그러지 않으면 남의 학원 차량으로 편성을 세울 수
     * 있고, {@code uk_route_bus_weekday_direction} 에 학원이 없어 그 편성이 남의 학원 편성과 자리를
     * 다툰다.
     */
    public RouteDetailResponse register(AuthUser requester, RouteRegisterRequest request) {
        RoutePlan plan = planOf(request);
        assertOwnBus(requester, plan.busId());
        List<Long> stopIds = orderOf(request.stopIds());
        routeStopArranger.resolve(requester.academyId(), stopIds);

        Route route = Route.register(requester.academyId(), plan);
        return enforcingUniqueSlot(requester.academyId(), plan, () -> {
            routeRepository.save(route);
            routeStopArranger.replace(route.getId(), requester.academyId(), stopIds);
            return routeDetailAssembler.assemble(route);
        });
    }

    /**
     * 편성을 고친다(§5.9) — 대상이 다른 학원이면 {@code 404 ROUTE_NOT_FOUND} 다.
     *
     * <p>유일성 조합을 <b>실제로 옮기는</b> 수정에만 중복 검사를 건다({@link Route#movesSlot}) —
     * 같은 값을 그대로 다시 보내는 요청이 자기 자신을 중복으로 세는 것을 막는다.
     */
    public RouteDetailResponse update(AuthUser requester, Long routeId, RouteUpdateRequest request) {
        Route route = findOwnRoute(requester, routeId);
        RoutePlan plan = planOf(request);
        if (plan.busId() != null) {
            assertOwnBus(requester, plan.busId());
        }
        if (request.stopIds() != null) {
            routeStopArranger.resolve(requester.academyId(), request.stopIds());
        }
        Supplier<RouteDetailResponse> apply = () -> applyUpdate(requester, route, plan, request.stopIds());
        return route.movesSlot(plan)
                ? enforcingUniqueSlot(requester.academyId(), merged(route, plan), apply)
                : apply.get();
    }

    /**
     * 편성을 삭제한다(§5.9) — soft delete 가 아니라 <b>행을 지운다</b>({@code route} 에
     * {@code deleted_at} 컬럼이 부재하고 ERD §7.1 의 삭제 방식 표에도 등재돼 있지 않다).
     *
     * <p>정차 순서는 {@code fk_route_stop_route} 의 {@code ON DELETE CASCADE} 로 함께 사라진다 —
     * 편성 없는 정차 순서는 가리키는 대상이 없어 남길 이유가 부재하다. 확정 노선
     * ({@code route_version}·{@code run_stop})은 별개 레코드라 영향받지 않는다(ERD §3.3).
     */
    public void delete(AuthUser requester, Long routeId) {
        routeRepository.delete(findOwnRoute(requester, routeId));
    }

    /**
     * 정차 순서를 전략 포트로 다시 매긴다(RTE-09, §5.9).
     *
     * <p><b>부르는 것은 이 엔드포인트뿐이다.</b> 편성·수정에 자동 트리거를 달지 않는 것이 Ruling 180
     * 의 요지다 — 거리·시간·정원 가중치가 미확정(PRD §10.1 G)이라, 기준이 정해지기 전에 재배열이
     * 조용히 돌면 관리자가 손으로 정한 차례가 이유 없이 뒤집힌다.
     *
     * <p>{@code fixedStops} 는 비운다 — 경유 지점(RTE-10)은 회차에 매달리는 것이라
     * ({@code waypoint.run_id}) 학기 단위 편성에는 그 자리가 부재하다. 경유 지점 지정은 Phase 8 이다.
     */
    public RouteDetailResponse optimize(AuthUser requester, Long routeId, RouteOptimizeRequest request) {
        Route route = findOwnRoute(requester, routeId);
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

    /** 수정 본체 — 조합 이동 여부와 무관하게 같은 순서로 돌아야 해서 한 곳에 모은다. */
    private RouteDetailResponse applyUpdate(AuthUser requester, Route route, RoutePlan plan,
            List<Long> stopIds) {
        route.update(plan);
        if (stopIds != null) {
            routeStopArranger.replace(route.getId(), requester.academyId(), stopIds);
        }
        return routeDetailAssembler.assemble(route);
    }

    private List<OrderableStop> orderableStopsOf(List<Long> stopIds, Map<Long, Stop> stops) {
        return stopIds.stream()
                .map(stops::get)
                .map(stop -> new OrderableStop(stop.getId(),
                        new GeoPoint(stop.getLat(), stop.getLng()), RIDER_COUNT_UNKNOWN))
                .toList();
    }

    private Route findOwnRoute(AuthUser requester, Long routeId) {
        return routeRepository.findByIdAndAcademyId(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
    }

    private void assertOwnBus(AuthUser requester, Long busId) {
        if (busRepository.findByIdAndAcademyId(busId, requester.academyId()).isEmpty()) {
            throw new BusinessException(ErrorCode.BUS_NOT_FOUND);
        }
    }

    /** {@code stop_ids} 를 주지 않은 편성은 정차지 없이 시작한다 — 칸을 먼저 잡는 조작이 실재한다. */
    private List<Long> orderOf(List<Long> stopIds) {
        return stopIds == null ? List.of() : stopIds;
    }

    private RoutePlan planOf(RouteRegisterRequest request) {
        return new RoutePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), request.name(), request.active());
    }

    private RoutePlan planOf(RouteUpdateRequest request) {
        return new RoutePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), request.name(), request.active());
    }

    /** 선검사가 볼 조합 — 요청이 준 값이 우선이고 주지 않은 항목은 지금 편성의 값이다. */
    private RoutePlan merged(Route route, RoutePlan plan) {
        return new RoutePlan(
                plan.busId() == null ? route.getBusId() : plan.busId(),
                plan.weekday() == null ? route.getWeekday() : plan.weekday(),
                plan.direction() == null ? route.getDirection() : plan.direction(),
                null, null);
    }

    /**
     * 유일성 조합을 강제하며 작업을 실행한다 — 위반은 선검사에서든 DB 거부에서든 같은
     * {@code 409 DUPLICATE_ROUTE} 다(Ruling 180).
     *
     * <p><b>선검사만으로는 부족하다.</b> 동시 요청 2건은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다
     * 선검사를 지나고, 그 뒤 {@code uk_route_bus_weekday_direction} 이 하나를 거부한다. 그 거부를
     * 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 편성돼 있다" 가 구별되지 않는다
     * ({@code BusCommandService}·{@code ScheduleCommandService} 가 같은 자리를 이미 푼다).
     *
     * <p>{@code action} 실행 후 즉시 flush 하는 것이 수정 경로 때문에 필요하다 — 조합 변경은 <b>변경
     * 감지</b>로만 DB 에 닿아서, flush 하지 않으면 {@code UPDATE} 가 커밋 시점까지 미뤄지고 제약
     * 위반이 이 {@code try} 밖에서 터진다. 편성 경로는 {@code IDENTITY} 키를 받으려고 {@code save()}
     * 시점에 이미 나가므로 flush 가 없어도 잡힌다 — <b>두 경로가 다르다.</b>
     *
     * <p>{@link jakarta.persistence.EntityManager} 가 아니라 <b>저장소의</b> flush 를 부르는 것도
     * 같은 이유로 중요하다 — 예외 번역({@code DataIntegrityViolationException})은 {@code @Repository}
     * 빈을 거칠 때만 붙어, {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를
     * 그대로 지나친다.
     */
    private <T> T enforcingUniqueSlot(Long academyId, RoutePlan slot, Supplier<T> action) {
        if (routeRepository.existsByAcademyIdAndBusIdAndWeekdayAndDirection(
                academyId, slot.busId(), slot.weekday(), slot.direction())) {
            throw new BusinessException(ErrorCode.DUPLICATE_ROUTE);
        }
        try {
            T result = action.get();
            routeRepository.flush();
            return result;
        } catch (DataIntegrityViolationException e) {
            if (isSlotViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_ROUTE);
            }
            throw e;
        }
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 유일성 제약인지만 본다. */
    private boolean isSlotViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && ROUTE_SLOT_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }
}
