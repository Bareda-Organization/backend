package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.run.event.RunEndedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/** 운행 종료 알림의 문구(API_SPEC §9.7 {@code run_ended}) — 수신자는 관계자이고 호차는 {@code bus_no} 칸에 싣는다. */
@Component
public class RunEndedComposer implements NotificationComposer<RunEndedEvent> {

    private static final String TITLE = "운행 종료 안내";

    private static final String BODY = "회차 운행이 종료되었습니다.";

    @Override
    public NotificationMessage compose(RunEndedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
