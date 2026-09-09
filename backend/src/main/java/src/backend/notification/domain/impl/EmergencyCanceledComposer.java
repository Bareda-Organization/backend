package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/** 비상 신고 취소 알림의 문구(EXC-04, Phase 11 T2 목표 9) — 수신자는 {@link EmergencyRaisedComposer} 와 같다. */
@Component
public class EmergencyCanceledComposer implements NotificationComposer<EmergencyCanceledEvent> {

    private static final String TITLE = "비상 신고 취소";

    @Override
    public NotificationMessage compose(EmergencyCanceledEvent subject) {
        return new NotificationMessage(TITLE, "%s 차량의 비상 신고가 취소되었습니다.".formatted(subject.busNo()));
    }
}
