package src.backend.routing.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.Route;
import src.backend.routing.repository.RouteRepository;

/**
 * 요청자 학원 소속 노선·차량 조회 — 다른 학원 자원은 존재하지 않는 것으로 답한다(§1.5). 호출자의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class RouteOwnership {

    private final RouteRepository routeRepository;

    private final BusRepository busRepository;

    /** 요청자 학원의 노선을 찾는다 — 없거나 다른 학원이면 {@code 404 ROUTE_NOT_FOUND}. */
    public Route findOwnRoute(AuthUser requester, Long routeId) {
        return routeRepository.findByIdAndAcademyId(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
    }

    /** 정차 목록을 갈아 끼울 요청은 편성 행을 잠그고 시작한다 — 같은 노선의 겹친 저장·최적화가 순서대로 처리된다. */
    public Route findOwnRouteForUpdate(AuthUser requester, Long routeId) {
        return routeRepository.findByIdAndAcademyIdForUpdate(routeId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
    }

    /** 요청자 학원의 차량이 아니면 {@code 404 BUS_NOT_FOUND}. */
    public void assertOwnBus(AuthUser requester, Long busId) {
        if (busRepository.findByIdAndAcademyId(busId, requester.academyId()).isEmpty()) {
            throw new BusinessException(ErrorCode.BUS_NOT_FOUND);
        }
    }
}
