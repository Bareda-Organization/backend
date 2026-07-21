package src.backend.location.source;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import src.backend.location.command.BusLocationCommandService;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.source.MockBusSimulationPlan.Leg;

/**
 * 버스 Mock 위치 소스 — {@link MockLocationSource}와 동일한 삼각파 보간 방식을 버스 단위로
 * 미러링한다(F1). {@link LocationSimulationScheduler}가 다형적으로 tick 하므로 이 클래스 추가만으로
 * 스케줄러 변경 없이 즉시 굴러간다.
 */
@Component
public class MockBusLocationSource implements LocationSource {

    private final MockBusSimulationPlan plan;
    private final BusLocationCommandService busLocationCommandService;
    private final boolean enabled;
    private final double step;

    private final Map<Long, Double> phaseByBus = new ConcurrentHashMap<>();
    private volatile List<Leg> legs;

    public MockBusLocationSource(MockBusSimulationPlan plan,
                                 BusLocationCommandService busLocationCommandService,
                                 @Value("${app.location.bus-mock.enabled:true}") boolean enabled,
                                 @Value("${app.location.mock.step:0.08}") double step) {
        this.plan = plan;
        this.busLocationCommandService = busLocationCommandService;
        this.enabled = enabled;
        this.step = step;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }

    @Override
    public void tick() {
        List<Leg> current = legs;
        if (current == null || current.isEmpty()) {
            current = plan.build();   // 시드가 준비되기 전이면 매 틱 재시도
            legs = current;
        }
        for (Leg leg : current) {
            double phase = (phaseByBus.merge(leg.busId(), step, Double::sum)) % 2.0;
            double t = 1.0 - Math.abs(phase - 1.0);   // 삼각파: 0 → 1 → 0
            double lat = leg.startLat() + (leg.endLat() - leg.startLat()) * t;
            double lng = leg.startLng() + (leg.endLng() - leg.startLng()) * t;
            busLocationCommandService.ingest(leg.tenantId(), leg.busId(), lat, lng, LocationOrigin.MOCK);
        }
    }

    @Override
    public String label() {
        return "mock-bus-gps";
    }
}
