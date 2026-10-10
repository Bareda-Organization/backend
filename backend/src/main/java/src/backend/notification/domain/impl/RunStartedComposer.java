package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunStartedEvent;

/**
 * 관계자용 운행 시작 알림의 문구(API_SPEC §9.7 {@code run_started}) — 회차 단위 알림이라 학생 이름·노선을 싣지 않는다
 * ({@link RouteChangedComposer} 와 같은 이유). 학부모·학생 몫은 자녀 이름을 싣는 {@link RunStartedChildComposer}
 * 가 맡는다(Ruling 868).
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
