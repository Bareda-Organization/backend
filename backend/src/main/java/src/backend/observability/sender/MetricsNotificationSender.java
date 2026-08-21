package src.backend.observability.sender;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 *
 * <p>{@code NotificationCommandServiceImpl.notify()} 는 {@code @Transactional} 이고, fan-out
 * ({@code notificationSenders.forEach(...)})이 그 트랜잭션 안에서 돈다. {@link Order}
 * ({@link Ordered#LOWEST_PRECEDENCE})로 이 sender 를 목록 맨 뒤로 고정하는 이유 3가지:
 * ① 계측이 항상 마지막 순번이라, 앞선 실제 알림 발송(로그 기록·WebSocket push)을 계측 코드가
 * 절대 막지 못한다. ② 앞 sender({@code WebSocketNotificationSender} 등)가 예외를 던져
 * 트랜잭션이 롤백되면 이 sender 자체가 호출되지 않으므로 카운터도 함께 증가를 멈춘다 — 저장은
 * 롤백됐는데 카운터만 올라가는 과다 계상이 사라진다. ③ {@code @Order} 가 없으면 컴포넌트
 * 스캔 순회 순서(빌드 산출물이 jar 인지 클래스 디렉터리인지에 따라 달라질 수 있음)에 목록 순서가
 * 좌우되므로, 이 계측 sender 가 먼저 와 앞선 sender 의 예외 전에 카운터를 올려버릴 위험이
 * 남는다 — 그 위험을 계측 도입 이전 수준(fan-out 에 예외 격리가 없는 기존 도메인 결함)으로
 * 되돌린다.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
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
