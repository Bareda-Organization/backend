package src.backend.location.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.StopRepository;

/**
 * 버스 Mock 시뮬레이션의 "이동 계획" — {@link MockSimulationPlan}과 동일한 이유(자기호출 방지로
 * 트랜잭션이 걸리게)로 별도 빈으로 분리한다.
 */
@Component
public class MockBusSimulationPlan {

    private final BusRepository busRepository;
    private final StopRepository stopRepository;

    public MockBusSimulationPlan(BusRepository busRepository, StopRepository stopRepository) {
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
    }

    /** 시뮬레이션 대상(노선이 배정된 버스)의 이동 구간. 출발=노선 첫 정류장, 도착=마지막 정류장(학원). */
    @Transactional(readOnly = true)
    public List<Leg> build() {
        List<Leg> legs = new ArrayList<>();
        for (Bus bus : busRepository.findAll()) {
            if (bus.getRoute() == null) {
                continue;
            }
            List<Stop> stops = stopRepository.findByRouteIdOrderBySeqAsc(bus.getRoute().getId());
            if (stops.isEmpty()) {
                continue;
            }
            Stop start = stops.get(0);
            Stop end = stops.get(stops.size() - 1);
            legs.add(new Leg(bus.getId(), bus.getTenant().getId(),
                    start.getLat(), start.getLng(), end.getLat(), end.getLng()));
        }
        return legs;
    }

    /** 버스 1대의 이동 구간(출발→도착 좌표). */
    public record Leg(Long busId, Long tenantId,
                      double startLat, double startLng,
                      double endLat, double endLng) {
    }
}
