package src.backend.routing.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import src.backend.bus.entity.Bus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.PlannedRoute;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;
import src.backend.student.entity.Student;
import src.backend.tenant.entity.Tenant;

/**
 * 노선 순수 계산. 저장·version 채번·권한 검사를 하지 않으므로 시뮬레이션(BE-4)과 실제 저장이 공유한다.
 * 교체 후보가 아니라 concrete 클래스로 둔다(§11.3).
 */
@Component
public class RoutePlanComputer {

    private final RouteEngine routeEngine;
    private final MapRouteClient mapRouteClient;
    private final int maxWaypoints;

    public RoutePlanComputer(RouteEngine routeEngine,
                             MapRouteClient mapRouteClient,
                             @Value("${routing.max-waypoints:7}") int maxWaypoints) {
        this.routeEngine = routeEngine;
        this.mapRouteClient = mapRouteClient;
        this.maxWaypoints = maxWaypoints;
    }

    /** 저장하지 않고 노선을 계산한다. 정원 초과를 검사하지 않는다 — 시뮬레이션이 초과 상태도 보여줘야 하기 때문. */
    public PlannedRoute computeRoute(Bus bus, RouteDirection direction, Map<Long, LatLng> studentPoints) {
        LatLng depot = requireDepot(bus.getTenant());
        List<Long> optimizedOrder = routeEngine.optimizeOrder(depot, studentPoints);
        List<Long> stopOrder = direction == RouteDirection.DROPOFF ? optimizedOrder : reversed(optimizedOrder);
        List<LatLng> waypoints = buildWaypoints(depot, stopOrder, studentPoints, direction);
        RouteResult routeResult = resolveRoute(waypoints);
        List<Double> cumulative = cumulativeSeconds(routeResult.legDurationsS());
        int stopWaypointOffset = direction == RouteDirection.DROPOFF ? 1 : 0;
        List<LatLng> stopPoints = new ArrayList<>(stopOrder.size());
        List<Long> stopEtaSeconds = new ArrayList<>(stopOrder.size());
        for (int i = 0; i < stopOrder.size(); i++) {
            stopPoints.add(studentPoints.get(stopOrder.get(i)));
            stopEtaSeconds.add(Math.round(cumulative.get(i + stopWaypointOffset)));
        }
        return new PlannedRoute(List.copyOf(stopOrder), List.copyOf(stopPoints), List.copyOf(stopEtaSeconds),
                routeResult.totalDistanceM(), routeResult.totalDurationS(), routeResult.polyline());
    }

    /**
     * 학생 1명의 방향별 좌표. 없으면 null — 시뮬레이션이 override 로 덮어쓸 수 있어 개별 해석이 필요하다.
     *
     * ⚠️ PICKUP 은 <b>학생 자체 좌표(pickupLat/Lng)가 있으면 그것을 우선</b>하고, 없을 때만
     * 공유 정류장(boardingStop)으로 떨어진다(OVERVIEW 결정 D-K). 이 우선순위가 없으면
     * 학부모가 등원 위치를 바꿔도(BE-10 이 pickupLat/Lng 에 저장) 경로 계산은 계속 정류장을 읽어
     * <b>변경이 조용히 무효</b>가 된다. 반대로 Stop.lat/lng 를 고치면 같은 정류장을 쓰는
     * 다른 학생까지 함께 끌려가므로, Stop 은 절대 수정하지 않는다.
     */
    public static LatLng pointOf(Student student, RouteDirection direction) {
        if (direction == RouteDirection.PICKUP) {
            if (student.getPickupLat() != null && student.getPickupLng() != null) {
                return new LatLng(student.getPickupLat(), student.getPickupLng());
            }
            return student.getBoardingStop() == null ? null
                    : new LatLng(student.getBoardingStop().getLat(), student.getBoardingStop().getLng());
        }
        return (student.getDropoffLat() == null || student.getDropoffLng() == null) ? null
                : new LatLng(student.getDropoffLat(), student.getDropoffLng());
    }

    /** 노선 계산 기준점(학원 위치). 미설정이면 다른 어떤 검사보다 먼저 실패시킨다. */
    public LatLng requireDepot(Tenant tenant) {
        if (tenant.getLat() == null || tenant.getLng() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "학원 위치(depot)가 설정되지 않았습니다");
        }
        return new LatLng(tenant.getLat(), tenant.getLng());
    }

    /** DROPOFF: [depot, ...순서대로]. PICKUP: [...역순, depot] — 대칭거리에서 depot고정 최적경로를 뒤집어도 총거리는 동일하다는 성질 이용. */
    private List<LatLng> buildWaypoints(LatLng depot, List<Long> stopOrder, Map<Long, LatLng> points,
                                        RouteDirection direction) {
        List<LatLng> waypoints = new ArrayList<>();
        if (direction == RouteDirection.DROPOFF) {
            waypoints.add(depot);
            for (Long id : stopOrder) {
                waypoints.add(points.get(id));
            }
        } else {
            for (Long id : stopOrder) {
                waypoints.add(points.get(id));
            }
            waypoints.add(depot);
        }
        return waypoints;
    }

    private List<Long> reversed(List<Long> order) {
        List<Long> copy = new ArrayList<>(order);
        Collections.reverse(copy);
        return copy;
    }

    private List<Double> cumulativeSeconds(List<Double> legDurationsS) {
        List<Double> cumulative = new ArrayList<>(legDurationsS.size() + 1);
        cumulative.add(0.0);
        double running = 0;
        for (double leg : legDurationsS) {
            running += leg;
            cumulative.add(running);
        }
        return cumulative;
    }

    /** waypoint 가 상한을 넘으면 경계를 공유하는 구간으로 나눠 여러 번 호출 후 병합한다. */
    private RouteResult resolveRoute(List<LatLng> waypoints) {
        if (waypoints.size() <= maxWaypoints) {
            return mapRouteClient.route(waypoints);
        }
        double totalDistance = 0;
        double totalDuration = 0;
        List<Double> legDurations = new ArrayList<>();
        StringBuilder polylineBuilder = new StringBuilder("[");
        boolean first = true;
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + maxWaypoints - 1, waypoints.size() - 1);
            RouteResult chunkResult = mapRouteClient.route(waypoints.subList(start, end + 1));
            totalDistance += chunkResult.totalDistanceM();
            totalDuration += chunkResult.totalDurationS();
            legDurations.addAll(chunkResult.legDurationsS());
            String p = chunkResult.polyline();
            if (!first) {
                polylineBuilder.append(',');
            }
            if (p != null && p.length() >= 2) {
                polylineBuilder.append(p, 1, p.length() - 1);
            }
            first = false;
            start = end;
        }
        polylineBuilder.append(']');
        return new RouteResult(totalDistance, totalDuration, legDurations, polylineBuilder.toString());
    }
}
