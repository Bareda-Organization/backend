package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.ApprovalRequestedEvent;

/**
 * ②구간 승인 대기 알림의 문구(API_SPEC §9.7 {@code approval_requested}) — 수신자는 관계자다.
 *
 * <p>본문에 학생 이름·주소를 싣지 않는 이유는 {@link IntentChangedComposer} 와 같다.
 */
@Component
public class ApprovalRequestedComposer implements NotificationComposer<ApprovalRequestedEvent> {

    private static final String TITLE = "탑승 변경 승인 요청";

    private static final String BODY = "학부모가 요청한 탑승 변경이 승인 대기 중입니다. 회차 마감 전 처리해 주세요.";

    @Override
    public NotificationMessage compose(ApprovalRequestedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
