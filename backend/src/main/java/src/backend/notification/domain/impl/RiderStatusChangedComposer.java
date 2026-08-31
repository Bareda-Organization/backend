package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 승차·하차 학부모 알림(BRD-01·02, API_SPEC §4.6) 문구 — {@link IntentChangedComposer} 와 같은
 * 형태로 상태값 하나로 분기한다. 자녀 이름을 본문에 넣지 않는다 — {@code IntentChangedComposer} 의
 * 판단(§6.3 L3, 신원 정보를 알림 본문에 싣지 않는다)과 같은 근거이며, 여기서는 수신자가 그 자녀의
 * 보호자뿐이라 이름이 없어도 무엇에 대한 알림인지 헷갈리지 않는다.
 */
@Component
public class RiderStatusChangedComposer implements NotificationComposer<RiderStatusChangedEvent> {

    private static final String TITLE = "승하차 안내";
    private static final String BODY_BOARDED = "자녀가 버스에 탑승했습니다.";
    private static final String BODY_ALIGHTED = "자녀가 버스에서 하차했습니다.";

    @Override
    public NotificationMessage compose(RiderStatusChangedEvent subject) {
        String body = "boarded".equals(subject.status()) ? BODY_BOARDED : BODY_ALIGHTED;
        return new NotificationMessage(TITLE, body);
    }
}
