package src.backend.sos.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import src.backend.sos.service.spec.SosService;

/**
 * SOS 3분 미확인 에스컬레이션 트리거 — {@code LocationSimulationScheduler}와 같은 얇은 타이머 역할만
 * 맡고, 실제 판정·알림 로직은 {@link SosService#escalateOverdue}에 둔다.
 * 매 실행마다 전체 OPEN 이벤트를 다시 훑지만, {@code NotificationService}의 dedupKey 멱등 덕분에
 * 이미 에스컬레이션된 이벤트는 조용히 무시되므로 중복 알림 걱정 없이 단순 폴링으로 충분하다.
 */
@Component
public class SosEscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SosEscalationScheduler.class);

    private final SosService sosService;

    public SosEscalationScheduler(SosService sosService) {
        this.sosService = sosService;
    }

    @Scheduled(fixedDelayString = "${app.sos.escalation-check-ms:30000}")
    public void tick() {
        try {
            sosService.escalateOverdue();
        } catch (Exception e) {
            // 한 번의 실패가 다음 주기 판정을 막지 않도록 삼킨다(로그만 남김).
            log.warn("[sos] 에스컬레이션 점검 실패: {}", e.getMessage());
        }
    }
}
