package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.IntentChangedEvent;

/**
 * 탑승 의사 즉시 반영 알림의 문구(API_SPEC §9.7 {@code intent_changed}) — 수신자는 관계자(학원 재직
 * 스태프 전원)다.
 *
 * <p>본문에 학생 이름·주소를 싣지 않는다({@link RouteChangedComposer} 와 같은 근거, FEATURE_SPEC
 * §6.3 L3 등급) — 어느 학생인지는 명단 화면에서 인증된 접근으로만 확인해야 한다. {@code riding} 값에
 * 따라 문구만 갈고 학생 식별자는 담지 않는다.
 */
@Component
public class IntentChangedComposer implements NotificationComposer<IntentChangedEvent> {

    private static final String TITLE = "탑승 의사 변경 안내";

    private static final String BODY_ON = "학생 한 명이 탑승으로 전환했습니다. 명단에서 확인해 주세요.";

    private static final String BODY_OFF = "학생 한 명이 미탑승으로 전환했습니다. 명단에서 확인해 주세요.";

    @Override
    public NotificationMessage compose(IntentChangedEvent subject) {
        return new NotificationMessage(TITLE, subject.riding() ? BODY_ON : BODY_OFF);
    }
}
