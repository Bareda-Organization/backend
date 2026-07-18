package src.backend.location.source;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import src.backend.location.command.LocationCommandService;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.source.MockSimulationPlan.Leg;

/**
 * Mock GPS 소스 — 실제 디바이스 없이 학생들이 정류장→학원 사이를 오가는 좌표 스트림을 생성한다(MVP).
 *
 * <p>매 틱마다 각 학생의 진행도(phase)를 조금씩 올리고, 출발↔도착 좌표를 선형보간해
 * {@link LocationCommandService#ingest} 로 밀어넣는다. 진행도는 삼각파(0→1→0)라 버스가 목적지에 갔다가
 * 되돌아오길 반복해 데모에서 "움직이는 점"으로 보인다. 저장·조회 로직은 실 GPS 와 완전히 동일하며,
 * 실 연동 시 이 소스를 끄고({@code app.location.mock.enabled=false}) {@link PhoneGpsSource} 로 바꾸면 된다.
 */
@Component
public class MockLocationSource implements LocationSource {

    private final MockSimulationPlan plan;
    private final LocationCommandService locationCommandService;
    private final boolean enabled;
    private final double step;

    private final Map<Long, Double> phaseByStudent = new ConcurrentHashMap<>();
    private volatile List<Leg> legs;

    public MockLocationSource(MockSimulationPlan plan,
                              LocationCommandService locationCommandService,
                              @Value("${app.location.mock.enabled:true}") boolean enabled,
                              @Value("${app.location.mock.step:0.08}") double step) {
        this.plan = plan;
        this.locationCommandService = locationCommandService;
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
            double phase = (phaseByStudent.merge(leg.studentId(), step, Double::sum)) % 2.0;
            double t = 1.0 - Math.abs(phase - 1.0);   // 삼각파: 0 → 1 → 0
            double lat = leg.startLat() + (leg.endLat() - leg.startLat()) * t;
            double lng = leg.startLng() + (leg.endLng() - leg.startLng()) * t;
            locationCommandService.ingest(leg.tenantId(), leg.studentId(), lat, lng, LocationOrigin.MOCK);
        }
    }

    @Override
    public String label() {
        return "mock-gps";
    }
}
