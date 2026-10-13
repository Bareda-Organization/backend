package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunCanceledEvent;

/**
 * 확정 회차 취소 알림의 문구(API_SPEC §9.7 {@code run_canceled}) — 수신자는 기사·동승자다. 회차를 특정하는 노선·시각은 싣지
 * 않는다({@link RouteChangedComposer} 와 같은 근거) — 앱에서 확인하게 한다.
 */
@Component
public class RunCanceledComposer implements NotificationComposer<RunCanceledEvent> {

    private static final String TITLE = "운행 취소 안내";

    private static final String BODY = "배정된 회차가 취소되었습니다. 앱에서 운행 일정을 확인해 주세요.";

    @Override
    public NotificationMessage compose(RunCanceledEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
