package src.backend.routing.command;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.routing.domain.RouteStopLimit;
import src.backend.routing.assembly.RouteDetailAssembler;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteStopsSaveRequest;
import src.backend.routing.dto.RouteUpdateRequest;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.RouteRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.command.StopMatcher;

/**
 * 고정 노선 편성·수정·삭제(RTE-01, API_SPEC §5.9 · Ruling 180).
 *
 * <p>순서 최적화(RTE-09)는 {@link RouteOptimizeService} 다 — 바뀌는 계기가 다르다(최적화 기준 확정
 * vs. 편성 유일성 조합).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RouteCommandService {

    private final RouteRepository routeRepository;

    private final RouteStopArranger routeStopArranger;

    private final RouteStopsSaver routeStopsSaver;

    private final RouteSlotGuard routeSlotGuard;

    private final RouteOwnership routeOwnership;

    private final RunRepository runRepository;

    private final RouteDetailAssembler routeDetailAssembler;

    /**
     * 고정 노선을 편성한다(§5.9) — 소속 학원은 토큰에서만 온다(§1.5).
     *
     * <p>차량을 <b>먼저</b> 학원으로 좁혀 확인한다 — 그러지 않으면 남의 학원 차량으로 편성을 세울 수
     * 있고, {@code uk_route_bus_weekday_direction} 에 학원이 없어 그 편성이 남의 학원 편성과 자리를
     * 다툰다.
     */
    public RouteDetailResponse register(AuthUser requester, RouteRegisterRequest request) {
        RouteStopLimit.assertWithin(request.stopIds());
        RoutePlan plan = RoutePlans.planOf(request);
        routeOwnership.assertOwnBus(requester, plan.busId());
        List<Long> stopIds = RoutePlans.orderOf(request.stopIds());
        routeStopArranger.resolve(requester.academyId(), stopIds);

        Route route = Route.register(requester.academyId(), plan);
        return routeSlotGuard.enforcingUniqueSlot(requester.academyId(), plan, () -> {
            routeRepository.save(route);
            routeStopArranger.replace(route.getId(), requester.academyId(), stopIds);
            runRepository.resetConfirmationFailures(requester.academyId());
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
        RouteStopLimit.assertWithin(request.stopIds());
        Route route = request.stopIds() == null ? routeOwnership.findOwnRoute(requester, routeId)
                : routeOwnership.findOwnRouteForUpdate(requester, routeId);
        RoutePlan plan = RoutePlans.planOf(request);
        if (plan.busId() != null) {
            routeOwnership.assertOwnBus(requester, plan.busId());
        }
        if (request.stopIds() != null) {
            routeStopArranger.resolve(requester.academyId(), request.stopIds());
        }
        Supplier<RouteDetailResponse> apply = () -> applyUpdate(requester, route, plan, request.stopIds());
        return route.movesSlot(plan)
                ? routeSlotGuard.enforcingUniqueSlot(requester.academyId(), RoutePlans.merged(route, plan), apply)
                : apply.get();
    }

    /**
     * 노선의 승하차지를 한 번에 저장한다(§5.9) — 추가·수정·삭제·순서를 한
     * 트랜잭션으로 반영한다.
     *
     * <p><b>고치기 전에 전부 검증한다.</b> 이미 있는 승하차지가 학원 밖이거나 중복이면 어떤 행도 고치지
     * 않은 채 {@code 422} 다 — 앞에서부터 고쳐 나가다 뒤에서 거부하면 트랜잭션 롤백에만 기대게 되고,
     * 같은 트랜잭션을 공유하는 호출자(시험 포함)에게는 반쯤 고친 상태가 보인다.
     *
     * <p>새 항목은 {@link StopMatcher} 가 정한다 — 50m 안에 이미 있으면 그것을 쓰고, 그 결과 같은
     * 승하차지가 두 번 담기면 마지막 검증이 {@code 422} 로 막는다(버스가 같은 자리에 두 번 서는 것).
     *
     * <p>편성·수정·이 저장은 모두 끝에서 그 학원 회차의 확정 실패 이력을 지운다 — 노선이 없어 영구 실패하던 회차가 고친 즉시 다음 틱에 다시
     * 시도된다(Ruling 703). 고쳐지지 않았으면 다시 실패해 재시도 간격이 처음부터 늘어난다.
     */
    public RouteDetailResponse saveStops(AuthUser requester, Long routeId, RouteStopsSaveRequest request) {
        RouteStopLimit.assertWithin(request.stops());
        Route route = routeOwnership.findOwnRouteForUpdate(requester, routeId);
        routeStopsSaver.save(requester, routeId, request);
        runRepository.resetConfirmationFailures(requester.academyId());
        return routeDetailAssembler.assemble(route);
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
        routeRepository.delete(routeOwnership.findOwnRoute(requester, routeId));
    }

    /** 수정 본체 — 조합 이동 여부와 무관하게 같은 순서로 돌아야 해서 한 곳에 모은다. */
    private RouteDetailResponse applyUpdate(AuthUser requester, Route route, RoutePlan plan,
            List<Long> stopIds) {
        route.update(plan);
        if (stopIds != null) {
            routeStopArranger.replace(route.getId(), requester.academyId(), stopIds);
        }
        runRepository.resetConfirmationFailures(requester.academyId());
        return routeDetailAssembler.assemble(route);
    }
}
