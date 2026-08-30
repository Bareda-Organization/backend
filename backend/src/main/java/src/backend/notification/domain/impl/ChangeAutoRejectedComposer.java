package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.ChangeRequestAutoRejectedEvent;

/**
 * 변경 요청 자동 거절 알림의 문구(API_SPEC §1.6·§9.7 {@code change_decided}) — 수신자는 신청 학부모다.
 *
 * <p>거절 사유를 싣지 않는다 — 관리자 거절({@code SignupDecidedComposer} 의 거절 문구)과 달리
 * <b>사람이 판단해 거절한 것이 아니라 마감까지 응답이 없었던 것</b>이라 실을 사유 자체가 없다. 대신
 * 기존 승하차 정보가 그대로 유지된다는 사실을 명시한다 — 그래야 학부모가 "요청이 사라진 것"으로
 * 오인해 같은 요청을 반복하지 않는다.
 */
@Component
public class ChangeAutoRejectedComposer implements NotificationComposer<ChangeRequestAutoRejectedEvent> {

    private static final String TITLE = "변경 요청 처리 안내";

    private static final String BODY = "요청하신 변경이 처리 기한 안에 확인되지 않아 반영되지 않았습니다. "
            + "기존 승하차 정보가 그대로 유지됩니다.";

    @Override
    public NotificationMessage compose(ChangeRequestAutoRejectedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
