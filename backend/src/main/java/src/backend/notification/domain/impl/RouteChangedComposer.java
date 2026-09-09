package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunRouteConfirmedEvent;

/**
 * 회차 확정 알림의 문구(API_SPEC §9.7 {@code route_changed}) — 수신자는 기사·동승자다.
 *
 * <p>본문에 학생 이름·주소를 싣지 않는다(FEATURE_SPEC §6.3) — 그 등급(L3)의 개인정보는 명단 화면에서
 * 인증된 접근으로만 봐야 하고, 알림은 기기 알림함·잠금화면에 그대로 노출돼 등급을 지키지 못한다.
 * 회차 자체를 특정하지 않는 것도 같은 이유다 — 회차를 특정하려면 노선·시각을 실어야 하는데, 그 값이
 * 곧 그 매니저가 어느 학생을 태우는지의 단서가 된다.
 */
@Component
public class RouteChangedComposer implements NotificationComposer<RunRouteConfirmedEvent> {

    private static final String TITLE = "노선 변경 안내";

    private static final String BODY = "배정된 회차의 노선이 확정·변경되었습니다. 앱에서 새 노선을 확인해 주세요.";

    @Override
    public NotificationMessage compose(RunRouteConfirmedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
