package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.AbsentRecordedEvent;

/**
 * 미탑승 확정 알림의 문구(API_SPEC §9.7 {@code absent}) — 수신자는 관계자다. 학생 이름은 본문에 싣지 않는다
 * ({@link IntentChangedComposer} 와 같은 근거 — L3 개인정보는 명단 화면에서만).
 */
@Component
public class AbsentComposer implements NotificationComposer<AbsentRecordedEvent> {

    private static final String TITLE = "미탑승 안내";

    private static final String BODY = "학생 한 명이 이 회차에 타지 않습니다. 명단에서 확인해 주세요.";

    @Override
    public NotificationMessage compose(AbsentRecordedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
