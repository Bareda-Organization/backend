package src.backend.routing.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import src.backend.routing.domain.BusCapacity;
import src.backend.routing.domain.LatLng;

/**
 * sweep 기반 버스 자동 배정 단위 테스트 — Spring 컨텍스트 없이 순수 알고리즘만 검증한다.
 */
class SweepAssignerTest {

    private final SweepAssigner assigner = new SweepAssigner();
    private final LatLng depot = new LatLng(37.5075, 127.0355);   // 한빛학원 depot(시드 좌표)

    @Test
    void withinCapacity_assignsAllStudentsAcrossBuses() {
        Map<Long, LatLng> points = Map.of(
                1L, new LatLng(37.4998, 127.0245),
                2L, new LatLng(37.5032, 127.0398),
                3L, new LatLng(37.5060, 127.0290));
        List<BusCapacity> buses = List.of(new BusCapacity(10L, 2), new BusCapacity(20L, 2));

        Optional<Map<Long, List<Long>>> result = assigner.assign(depot, points, buses);

        assertTrue(result.isPresent());
        Map<Long, List<Long>> assignment = result.get();
        List<Long> allAssigned = assignment.values().stream().flatMap(List::stream).toList();
        assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(allAssigned));
        assignment.values().forEach(group -> assertTrue(group.size() <= 2));
    }

    @Test
    void overCapacity_returnsEmpty() {
        Map<Long, LatLng> points = Map.of(
                1L, new LatLng(37.4998, 127.0245),
                2L, new LatLng(37.5032, 127.0398),
                3L, new LatLng(37.5060, 127.0290));
        List<BusCapacity> buses = List.of(new BusCapacity(10L, 1), new BusCapacity(20L, 1));

        assertTrue(assigner.assign(depot, points, buses).isEmpty());
    }

    @Test
    void singleBus_assignsAllToThatBus() {
        Map<Long, LatLng> points = Map.of(
                1L, new LatLng(37.4998, 127.0245),
                2L, new LatLng(37.5032, 127.0398));
        List<BusCapacity> buses = List.of(new BusCapacity(10L, 5));

        Map<Long, List<Long>> assignment = assigner.assign(depot, points, buses).orElseThrow();

        assertEquals(1, assignment.size());
        assertEquals(Set.of(1L, 2L), Set.copyOf(assignment.get(10L)));
    }

    @Test
    void samePoint_multipleStudents_allAssignedExactlyOnce() {
        Map<Long, LatLng> points = Map.of(
                1L, new LatLng(37.50, 127.03),
                2L, new LatLng(37.50, 127.03),
                3L, new LatLng(37.50, 127.03));
        List<BusCapacity> buses = List.of(new BusCapacity(10L, 2), new BusCapacity(20L, 2));

        Map<Long, List<Long>> assignment = assigner.assign(depot, points, buses).orElseThrow();

        List<Long> allAssigned = assignment.values().stream().flatMap(List::stream).toList();
        assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(allAssigned));
    }
}
