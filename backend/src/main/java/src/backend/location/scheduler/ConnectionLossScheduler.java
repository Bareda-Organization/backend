package src.backend.location.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import src.backend.location.service.spec.LocationService;

/**
 * WebSocket 연결 끊김 판정 트리거 — {@code SosEscalationScheduler}와 같은 얇은 타이머 역할만 하고,
 * 실제 유예시간 판정·알림은 {@link LocationService#checkOverdueDisconnections}에 둔다.
 */
@Component
public class ConnectionLossScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLossScheduler.class);

    private final LocationService locationService;

    public ConnectionLossScheduler(LocationService locationService) {
        this.locationService = locationService;
    }

    @Scheduled(fixedDelayString = "${app.connection.loss-check-ms:10000}")
    public void tick() {
        try {
            locationService.checkOverdueDisconnections();
        } catch (Exception e) {
            log.warn("[connection] 끊김 점검 실패: {}", e.getMessage());
        }
    }
}
