package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.ApprovalRequestedEvent;

/**
 * ②구간 변경 신청 접수 알림의 문구(API_SPEC §9.7 {@code approval_requested}) — 수신자는 학원 관계자다.
 *
 * <p>본문에 학생 이름·목적지를 싣지 않는다 — {@link RouteChangedComposer} 와 같은 근거(FEATURE_SPEC
 * §6.3, 개인정보는 인증된 화면에서만). 관계자는 이 알림을 보고 승인 큐 화면(§5.6, T4 소유)을 열어
 * 신청 내용을 확인한다.
 */
@Component
public class ApprovalRequestedComposer implements NotificationComposer<ApprovalRequestedEvent> {

    private static final String TITLE = "변경 승인 요청";

    private static final String BODY = "학부모의 일일 변경 요청이 접수되어 승인이 필요합니다. 앱에서 확인해 주세요.";

    @Override
    public NotificationMessage compose(ApprovalRequestedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
