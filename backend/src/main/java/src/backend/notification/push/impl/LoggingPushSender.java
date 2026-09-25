package src.backend.notification.push.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;

/**
 * 로그로 발송을 대신하는 로컬·데모 구현 — 실 채널은 {@link FcmPushSender}(Ruling 331). Firebase 키가 없는
 * 환경에서 아웃박스 2단 구조가 끝까지 도는지를 이 구현으로 확인한다.
 *
 * <p>{@code app.push.sender} 가 없으면 이 구현이 뜬다({@code matchIfMissing}) — 채널을 더할 때
 * 그 값 하나로 갈아끼우고, 호출부({@code NotificationDispatcher})는 손대지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.push.sender", havingValue = "logging", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    /**
     * prod 에서 이 구현이 뜨면 기동을 실패시킨다(Ruling 331) — 승하차 알림이 단말에 하나도 가지 않는데 로그에는
     * 발송으로 남는 상태를 조용히 운영하지 않게 한다. prod 는 {@code app.push.sender=fcm} 과 FCM 자격 증명을 주입한다.
     */
    public LoggingPushSender(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("prod 에서 푸시 발송이 로그 전용이다 — app.push.sender=fcm 과 FCM 자격 증명을 주입하라");
        }
    }

    @Override
    public void send(PushMessage message) {
        log.info("[push] account={} type={} title={}", message.recipientAccountId(), message.type(),
                message.title());
    }
}
