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
        // 두 값으로만 값을 좁혀 분기한다 — 예전엔 3항 연산자로 "boarded 가 아니면 무조건 하차" 로
        // 처리해, 되돌리기가 이 컴포저까지 도달하면 무슨 상태든 하차 문구가 나가는 결함이 있었다
        // (지금은 BoardingNotificationListener 가 되돌리기 이벤트를 걸러내 여기까진 안 오지만, 그
        // 방어를 이 컴포저 자체에도 둬 같은 형태의 결함이 다시 생기면 조용히 틀린 문구를 내는 대신
        // 예외로 드러나게 한다).
        String body = switch (subject.status()) {
            case "boarded" -> BODY_BOARDED;
            case "alighted" -> BODY_ALIGHTED;
            default -> throw new IllegalStateException("알 수 없는 승하차 상태: " + subject.status());
        };
        return new NotificationMessage(TITLE, body);
    }
}
