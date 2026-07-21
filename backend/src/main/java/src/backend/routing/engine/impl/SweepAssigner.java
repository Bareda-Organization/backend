package src.backend.routing.engine.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import src.backend.routing.domain.BusCapacity;
import src.backend.routing.domain.GeoMath;
import src.backend.routing.domain.LatLng;
import src.backend.routing.engine.spec.BusAssigner;

/**
 * sweep(방위각 정렬) 기반 버스 자동 배정 — depot 기준 방위각으로 학생을 정렬한 뒤,
 * 버스를 파라미터 순서대로 순회하며 좌석수까지 순서대로 채운다(F4).
 */
@Component
public class SweepAssigner implements BusAssigner {

    @Override
    public Optional<Map<Long, List<Long>>> assign(LatLng depot, Map<Long, LatLng> studentPoints,
                                                   List<BusCapacity> buses) {
        int totalCapacity = buses.stream().mapToInt(BusCapacity::seatCapacity).sum();
        if (studentPoints.size() > totalCapacity) {
            return Optional.empty();
        }

        List<Long> sortedStudentIds = studentPoints.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> GeoMath.bearingDegrees(depot, e.getValue())))
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, List<Long>> assignment = new LinkedHashMap<>();
        int cursor = 0;
        for (BusCapacity bus : buses) {
            List<Long> group = new ArrayList<>();
            while (group.size() < bus.seatCapacity() && cursor < sortedStudentIds.size()) {
                group.add(sortedStudentIds.get(cursor));
                cursor++;
            }
            assignment.put(bus.busId(), group);
        }
        return Optional.of(assignment);
    }
}
