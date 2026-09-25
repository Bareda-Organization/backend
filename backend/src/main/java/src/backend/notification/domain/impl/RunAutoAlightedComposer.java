package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.domain.spec.RunAutoAlightedSubject;

/**
 * 등원 최종 도착 처리의 자동 하차를 알리는 문구(API_SPEC §9.7 {@code alighting}) — 문구에 자녀
 * 이름을 싣는다(ATT-03, Ruling 225). 예전엔 "어느 자녀인지는 본문에 싣지 않는다"(신원 노출 우려)를
 * 근거로 뺐는데, 이름은 §6.3 L1(기본) 이라 그 우려가 잘못 적용됐다 — {@code alighting} 도 §9.7
 * on/off 토글 대상이라 {@link RiderStatusChangedComposer} 와 같은 근거로 이름이 필요하다.
 */
@Component
public class RunAutoAlightedComposer implements NotificationComposer<RunAutoAlightedSubject> {

    private static final String TITLE = "하차 안내";

    private static final String BODY = "%s 학생이 목적지에 도착해 하차 처리되었습니다.";

    @Override
    public NotificationMessage compose(RunAutoAlightedSubject subject) {
        return new NotificationMessage(TITLE, BODY.formatted(subject.studentName()));
    }
}
