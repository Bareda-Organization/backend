package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunAutoAlightedEvent;

/**
 * 등원 최종 도착 처리의 자동 하차를 알리는 문구(API_SPEC §9.7 {@code alighting}) — 수신자는
 * 학부모 1명이지만, <b>어느 자녀인지는 본문에 싣지 않는다</b> — 알림함·잠금화면에 그대로 노출되는
 * 채널에 학생 이름을 얹으면 {@link RouteChangedComposer} 가 피하는 것과 같은 노출이 된다.
 */
@Component
public class RunAutoAlightedComposer implements NotificationComposer<RunAutoAlightedEvent> {

    private static final String TITLE = "하차 안내";

    private static final String BODY = "자녀가 목적지에 도착해 하차 처리되었습니다.";

    @Override
    public NotificationMessage compose(RunAutoAlightedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
