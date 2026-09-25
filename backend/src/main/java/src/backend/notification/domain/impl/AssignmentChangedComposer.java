package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.manager.event.AssignmentChangedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 배치 변경 알림의 문구(API_SPEC §9.7 {@code assignment_changed}) — 수신자는 새로 배치된 매니저다. 회차를 특정하는
 * 노선·시각은 싣지 않는다({@link RouteChangedComposer} 와 같은 근거) — 앱에서 확인하게 한다.
 */
@Component
public class AssignmentChangedComposer implements NotificationComposer<AssignmentChangedEvent> {

    private static final String TITLE = "배치 변경 안내";

    private static final String BODY = "오늘 배정된 회차가 바뀌었습니다. 앱에서 운행 일정을 확인해 주세요.";

    @Override
    public NotificationMessage compose(AssignmentChangedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
