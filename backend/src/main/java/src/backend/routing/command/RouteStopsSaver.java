package src.backend.routing.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.routing.dto.RouteStopsSaveRequest;
import src.backend.student.command.StopMatcher;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 노선 승하차지 한 번에 저장의 단계(API_SPEC §5.9) — 검증 → 새 순서 만들기 → 정차 순서 교체. 호출자의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class RouteStopsSaver {

    private final RouteStopArranger routeStopArranger;

    private final MovingRunStopLock movingRunStopLock;

    /** 좌표 → 승하차지 확보(근접 병합 포함). 만드는 규칙이 이 한 곳에만 있도록 재사용한다(STU-05). */
    private final StopMatcher stopMatcher;

    /**
     * 요청의 승하차지를 고치고 노선의 정차 순서를 새 순서로 교체한다 — 이미 있는 승하차지 검증이 모든 수정보다 앞선다.
     */
    public void save(AuthUser requester, Long routeId, RouteStopsSaveRequest request) {
        Map<Long, Stop> existing = routeStopArranger.resolve(requester.academyId(), request.existingStopIds());
        assertNotRelocatingStopsOfMovingRun(requester, existing, request);
        List<Long> order = orderOf(requester, existing, request);
        routeStopArranger.resolve(requester.academyId(), order);
        routeStopArranger.replace(routeId, requester.academyId(), order);
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

    /** 요청 항목 순서대로 승하차지 id 를 모은다 — 기존 항목은 고쳐 쓰고 새 항목은 {@link StopMatcher} 가 정한다. */
    private List<Long> orderOf(AuthUser requester, Map<Long, Stop> existing, RouteStopsSaveRequest request) {
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
        return order;
    }
}
