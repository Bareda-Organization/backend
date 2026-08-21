package src.backend.observability.sender;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.NotificationLog;
import src.backend.notification.infrastructure.spec.NotificationSender;
import src.backend.observability.metrics.PipelineMetrics;

/**
 * 알림 발송을 계측한다. {@code NotificationCommandServiceImpl} 이 등록된
 * {@code NotificationSender} 를 전부 호출하므로, 구현체를 하나 더 얹는 것만으로 계측이 붙는다 —
 * 알림 서비스 코드는 수정하지 않는다.
 *
 * <p>중복 억제(dedupKey)로 저장을 건너뛴 경우에는 sender 가 호출되지 않는다. 따라서 이 카운터는
 * "시도 수"가 아니라 <b>실제 발송 수</b>이며, 알림 정지를 판별하는 값으로 쓸 수 있다.
 */
@Component
public class MetricsNotificationSender implements NotificationSender {

    private final PipelineMetrics metrics;

    public MetricsNotificationSender(PipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void send(NotificationLog notification) {
        metrics.notificationSent(notification.getType());
    }
}
