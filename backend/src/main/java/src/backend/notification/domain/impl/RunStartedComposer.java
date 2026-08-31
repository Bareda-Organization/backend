package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunStartedEvent;

/**
 * 운행 시작 알림의 문구(API_SPEC §9.7 {@code run_started}) — 수신자는 관계자·학부모·학생 셋이지만
 * 문구는 공통이다(대상마다 다른 정보를 실을 만큼 실릴 개인정보가 없다 — {@link RouteChangedComposer}
 * 와 같은 이유로 학생 이름·노선을 싣지 않는다).
 */
@Component
public class RunStartedComposer implements NotificationComposer<RunStartedEvent> {

    private static final String TITLE = "운행 시작 안내";

    private static final String BODY = "배정된 회차의 운행이 시작되었습니다.";

    @Override
    public NotificationMessage compose(RunStartedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
