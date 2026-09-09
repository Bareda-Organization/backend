package src.backend.notification.push.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;

/**
 * 로그로 발송을 대신하는 기본 구현 — 실 채널(FCM · APNs) 연동 전까지 아웃박스 2단 구조가 끝까지
 * 도는지를 이 구현으로 확인한다.
 *
 * <p>{@code app.push.sender} 가 없으면 이 구현이 뜬다({@code matchIfMissing}) — 채널을 더할 때
 * 그 값 하나로 갈아끼우고, 호출부({@code NotificationDispatcher})는 손대지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.push.sender", havingValue = "logging", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(PushMessage message) {
        log.info("[push] account={} type={} title={}", message.recipientAccountId(), message.type(),
                message.title());
    }
}
