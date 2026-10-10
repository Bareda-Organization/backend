package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RunStartedChildSubject;

/**
 * 학부모·학생용 운행 시작 알림의 문구(API_SPEC §9.7 {@code run_started}, Ruling 868) — 형제가 같은 회차를 타면 같은 문구
 * 2건이 와서 누구 버스인지 가를 수 없었으므로 자녀 이름을 싣는다. 관계자 몫은 {@link RunStartedComposer} 가 맡는다.
 */
@Component
public class RunStartedChildComposer implements NotificationComposer<RunStartedChildSubject> {

    private static final String TITLE = "운행 시작 안내";

    private static final String BODY = "%s 학생이 탈 버스의 운행이 시작되었습니다.";

    @Override
    public NotificationMessage compose(RunStartedChildSubject subject) {
        return new NotificationMessage(TITLE, BODY.formatted(subject.studentName()));
    }
}
