package src.backend.routing.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.ConstraintViolations;
import src.backend.global.request.ApiValues;
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
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.command.StopMatcher;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;

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

    private final BusRepository busRepository;

    private final RouteStopArranger routeStopArranger;

    private final MovingRunStopLock movingRunStopLock;

    private final RunRepository runRepository;

    private final RouteDetailAssembler routeDetailAssembler;

    private final RouteStopRepository routeStopRepository;

    /** 좌표 → 승하차지 확보(근접 병합 포함). 만드는 규칙이 이 한 곳에만 있도록 재사용한다(STU-05). */
    private final StopMatcher stopMatcher;

    /**
     * 고정 노선을 편성한다(§5.9) — 소속 학원은 토큰에서만 온다(§1.5).
     *
     * <p>차량을 <b>먼저</b> 학원으로 좁혀 확인한다 — 그러지 않으면 남의 학원 차량으로 편성을 세울 수
     * 있고, {@code uk_route_bus_weekday_direction} 에 학원이 없어 그 편성이 남의 학원 편성과 자리를
     * 다툰다.
     */
    public RouteDetailResponse register(AuthUser requester, RouteRegisterRequest request) {
        RouteStopLimit.assertWithin(request.stopIds());
        RoutePlan plan = planOf(request);
        assertOwnBus(requester, plan.busId());
        List<Long> stopIds = orderOf(request.stopIds());
        routeStopArranger.resolve(requester.academyId(), stopIds);

        Route route = Route.register(requester.academyId(), plan);
        return enforcingUniqueSlot(requester.academyId(), plan, () -> {
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
     * 노선의 승하차지를 한 번에 저장한다(§5.9, 2026-09-23 사용자 지시) — 추가·수정·삭제·순서를 한
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
     * 시도된다(R46-KFIXBE K-2, Ruling 703). 고쳐지지 않았으면 다시 실패해 재시도 간격이 처음부터 늘어난다.
     */
    public RouteDetailResponse saveStops(AuthUser requester, Long routeId, RouteStopsSaveRequest request) {
        RouteStopLimit.assertWithin(request.stops());
        Route route = findOwnRoute(requester, routeId);
        Map<Long, Stop> existing = routeStopArranger.resolve(requester.academyId(), request.existingStopIds());
        assertNotRelocatingStopsOfMovingRun(requester, existing, request);

        List<Long> order = new ArrayList<>(request.stops().size());
        for (RouteStopsSaveRequest.Item item : request.stops()) {
            if (item.stopId() != null) {
                existing.get(item.stopId()).relocate(item.name(), item.address(), item.lat(), item.lng());
                order.add(item.stopId());
                continue;
            }
            String address = item.address() == null || item.address().isBlank() ? item.name() : item.address();
            order.add(stopMatcher.matchOrCreate(requester.academyId(), new GeocodedPoint(item.lat(), item.lng(), address),
                    item.name()).getId());
        }
        routeStopArranger.resolve(requester.academyId(), order);
        routeStopArranger.replace(routeId, requester.academyId(), order);
        runRepository.resetConfirmationFailures(requester.academyId());
        return routeDetailAssembler.assemble(route);
    }

    /**
     * 좌표가 바뀌는 승하차지가 운행 중 회차의 노선에 서면 {@code 403 CHANGE_WINDOW_CLOSED} 다 — 판정은 {@link MovingRunStopLock} 이
     * 맡고, 여기서는 이 요청이 어느 승하차지의 좌표를 옮기는지만 가린다.
     */
    private void assertNotRelocatingStopsOfMovingRun(AuthUser requester, Map<Long, Stop> existing,
            RouteStopsSaveRequest request) {
        List<Long> relocated = request.stops().stream()
                .filter(item -> item.stopId() != null)
                .filter(item -> existing.get(item.stopId()).movesTo(item.lat(), item.lng()))
                .map(RouteStopsSaveRequest.Item::stopId)
                .toList();
        movingRunStopLock.assertNotRelocating(requester.academyId(), relocated);
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

    private boolean isSlotViolation(DataIntegrityViolationException e) {
        return ConstraintViolations.isViolationOf(e, ROUTE_SLOT_UNIQUE_CONSTRAINT);
    }
}
