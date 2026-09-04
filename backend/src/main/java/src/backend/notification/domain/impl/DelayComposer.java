package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.entity.DelayReason;

/**
 * 지연 알림의 문구(NTF-06, API_SPEC §4.9) — {@code message} 가 없을 때만 {@code reason} 프리셋 +
 * "현재 예상 지연 N분" 을 조립한다(Ruling 253 — 갱신 의미라 이전 신고와 합산하지 않는다).
 */
@Component
public class DelayComposer implements NotificationComposer<DelaySubject> {

    private static final String TITLE = "지연 알림";

    @Override
    public NotificationMessage compose(DelaySubject subject) {
        String body = subject.message() != null ? subject.message() : autoBody(subject);
        return new NotificationMessage(TITLE, body);
    }

    private String autoBody(DelaySubject subject) {
        return presetOf(subject.reason()) + " 현재 예상 지연 %d분입니다.".formatted(subject.minutes());
    }

    private String presetOf(DelayReason reason) {
        return switch (reason) {
            case TRAFFIC -> "교통 정체로 인해 지연되고 있습니다.";
            case WEATHER -> "기상 상황으로 인해 지연되고 있습니다.";
            case VEHICLE_CHECK -> "차량 점검으로 인해 지연되고 있습니다.";
            case PREV_STOP_WAIT -> "이전 승하차지 대기로 인해 지연되고 있습니다.";
        };
    }
}
