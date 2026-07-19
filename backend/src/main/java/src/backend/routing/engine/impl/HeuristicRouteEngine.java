package src.backend.routing.engine.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import src.backend.routing.domain.GeoMath;
import src.backend.routing.domain.LatLng;
import src.backend.routing.engine.spec.RouteEngine;

/**
 * sweep(방위각 정렬) + nearest-neighbor(그리디 구성) + 2-opt(개선) 휴리스틱.
 *
 * <p>버스 한 대의 이미 배정된 로스터를 순회하는 단일 차량 open-path 문제라, 원래 VRP sweep 기법의
 * "여러 차량으로 클러스터 분할" 역할은 필요 없다 — 여기서 sweep 은 결정적 초기 순서(동률 타이브레이커)만
 * 제공하고, 실제 경로는 nearest-neighbor 로 구성한 뒤 2-opt 로 개선한다. 거리 함수는 Haversine
 * (외부 호출 0) — 실제 도로거리·ETA 는 최종 순서 확정 후 {@code MapRouteClient} 1회 호출로 얻는다.
 */
@Component
public class HeuristicRouteEngine implements RouteEngine {

    private static final int MAX_TWO_OPT_PASSES = 1000;

    @Override
    public List<Long> optimizeOrder(LatLng depot, Map<Long, LatLng> studentPoints) {
        if (studentPoints.isEmpty()) {
            return List.of();
        }
        List<Long> sweepOrder = studentPoints.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> GeoMath.bearingDegrees(depot, e.getValue())))
                .map(Map.Entry::getKey)
                .toList();

        List<Long> tour = nearestNeighbor(depot, studentPoints, sweepOrder);
        return twoOpt(depot, studentPoints, tour);
    }

    private List<Long> nearestNeighbor(LatLng depot, Map<Long, LatLng> points, List<Long> tieBreakOrder) {
        List<Long> remaining = new ArrayList<>(tieBreakOrder);
        List<Long> tour = new ArrayList<>(remaining.size());
        LatLng current = depot;
        while (!remaining.isEmpty()) {
            Long nearest = null;
            double best = Double.MAX_VALUE;
            for (Long id : remaining) {
                double d = GeoMath.distanceMeters(current, points.get(id));
                if (d < best) {
                    best = d;
                    nearest = id;
                }
            }
            tour.add(nearest);
            remaining.remove(nearest);
            current = points.get(nearest);
        }
        return tour;
    }

    /**
     * open-path 2-opt — depot 을 인덱스 -1의 가상 노드로 취급해, 구간 [i,j] 를 뒤집었을 때
     * 바뀌는 두 경계 간선만 비교한다(내부 간선은 뒤집어도 거리가 같다). 스왑마다 총거리가
     * 엄격히 줄어들어 유한 스텝에 수렴하지만, 방어적으로 반복 상한을 둔다.
     */
    private List<Long> twoOpt(LatLng depot, Map<Long, LatLng> points, List<Long> initialTour) {
        List<Long> tour = new ArrayList<>(initialTour);
        int n = tour.size();
        if (n < 3) {
            return tour;
        }
        boolean improved = true;
        int guard = 0;
        while (improved && guard < MAX_TWO_OPT_PASSES) {
            improved = false;
            guard++;
            for (int i = 0; i < n - 1; i++) {
                LatLng a = (i == 0) ? depot : points.get(tour.get(i - 1));
                LatLng b = points.get(tour.get(i));
                for (int j = i + 1; j < n; j++) {
                    LatLng c = points.get(tour.get(j));
                    double before;
                    double after;
                    if (j == n - 1) {
                        before = GeoMath.distanceMeters(a, b);
                        after = GeoMath.distanceMeters(a, c);
                    } else {
                        LatLng d = points.get(tour.get(j + 1));
                        before = GeoMath.distanceMeters(a, b) + GeoMath.distanceMeters(c, d);
                        after = GeoMath.distanceMeters(a, c) + GeoMath.distanceMeters(b, d);
                    }
                    if (after < before - 1e-6) {
                        Collections.reverse(tour.subList(i, j + 1));
                        improved = true;
                        b = points.get(tour.get(i));
                    }
                }
            }
        }
        return tour;
    }
}
