package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 가입 결정 알림의 문구(API_SPEC §9.7 {@code signup_decided}) — 수락과 거절이 <b>다른 문구</b>를 쓴다.
 *
 * <p>거절 문구에 사유를 싣는 것이 이 구현의 요점이다. 사유를 빼면 거절된 사람은 무엇을 고쳐
 * 재신청해야 하는지 알 수단이 부재하고(§1.4), 재신청은 {@code rejected} 상태에서만 열리므로
 * (AUTH-03) 그대로 막힌 화면이 된다.
 */
@Component
public class SignupDecidedComposer implements NotificationComposer<SignupDecidedEvent> {

    private static final String ACCEPTED_TITLE = "가입 승인 안내";

    private static final String REJECTED_TITLE = "가입 거절 안내";

    @Override
    public NotificationMessage compose(SignupDecidedEvent subject) {
        return subject.accepted()
                ? new NotificationMessage(ACCEPTED_TITLE, "가입이 승인되었습니다. 로그인 후 이용해 주세요.")
                : new NotificationMessage(REJECTED_TITLE, "가입이 거절되었습니다. 사유: " + subject.rejectReason());
    }
}
