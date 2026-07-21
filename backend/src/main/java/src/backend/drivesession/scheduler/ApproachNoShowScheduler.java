package src.backend.drivesession.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import src.backend.drivesession.command.DriveSessionCommandService;

/**
 * APPROACH(도착 5분전)·NO_SHOW(도착+10분 미승차) 판정 트리거 — {@code SosEscalationScheduler}와 같은
 * 얇은 타이머 역할만 맡고, 실제 판정·알림 로직은 {@link DriveSessionCommandService#checkApproachAndNoShow}에 둔다.
 */
@Component
public class ApproachNoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApproachNoShowScheduler.class);

    private final DriveSessionCommandService driveSessionCommandService;

    public ApproachNoShowScheduler(DriveSessionCommandService driveSessionCommandService) {
        this.driveSessionCommandService = driveSessionCommandService;
    }

    @Scheduled(fixedDelayString = "${app.drivesession.approach-check-ms:15000}")
    public void tick() {
        try {
            driveSessionCommandService.checkApproachAndNoShow();
        } catch (Exception e) {
            // 한 번의 실패가 다음 주기 판정을 막지 않도록 삼킨다(로그만 남김).
            log.warn("[drivesession] 근접/미승차 점검 실패: {}", e.getMessage());
        }
    }
}
