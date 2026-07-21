package src.backend.location.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 실 GPS 소스(기사 단말) — {@link PhoneGpsSource}와 동일한 이유로 no-op이다: 서버가 끌어올 게
 * 없고, 좌표는 {@code POST /api/locations/bus}({@code LocationController.reportBusLocation}) →
 * {@code BusLocationCommandService.reportSelf} 경로로 직접 들어온다.
 *
 * <p>MVP 에서는 기본 비활성({@code app.location.bus-gps.enabled=false})이고, 실 연동 시
 * 이 플래그를 켜고 {@link MockBusLocationSource}를 끄면 교체가 끝난다.
 */
@Component
public class DriverGpsSource implements LocationSource {

    private final boolean enabled;

    public DriverGpsSource(@Value("${app.location.bus-gps.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }

    @Override
    public void tick() {
        // push 방식이라 서버가 끌어올 게 없다 — 좌표는 기사 앱의 POST /api/locations/bus 로 들어온다.
    }

    @Override
    public String label() {
        return "driver-gps";
    }
}
