package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.request.event.ChangeRequestDecidedEvent;

/**
 * ②구간 변경 요청 관리자 결정 알림의 문구(API_SPEC §5.6·§9.7 {@code change_decided}) — 승인·거절이
 * {@link src.backend.notification.domain.impl.SignupDecidedComposer} 와 같은 형태로 다른 문구를
 * 쓴다.
 *
 * <p>거절 문구에 사유를 싣는다 — {@code SignupDecidedComposer} 의 거절 문구와 같은 이유(사유가
 * 없으면 학부모가 무엇이 반려됐는지 알 수단이 없다). {@link ChangeAutoRejectedComposer}(자동 거절)가
 * 사유를 싣지 <b>않는</b> 것과 대비된다 — 그쪽은 사람이 판단해 거절한 것이 아니라 마감 도과라 실을
 * 사유 자체가 없다.
 */
@Component
public class ChangeDecidedComposer implements NotificationComposer<ChangeRequestDecidedEvent> {

    private static final String APPROVED_TITLE = "변경 요청 승인 안내";

    private static final String REJECTED_TITLE = "변경 요청 거절 안내";

    @Override
    public NotificationMessage compose(ChangeRequestDecidedEvent subject) {
        return subject.approved()
                ? new NotificationMessage(APPROVED_TITLE, "요청하신 승하차 변경이 승인되어 반영되었습니다.")
                : new NotificationMessage(REJECTED_TITLE, "요청하신 승하차 변경이 거절되었습니다. 사유: " + subject.rejectReason());
    }
}
