package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * ②구간 변경 요청 관리자 결정 알림의 문구(API_SPEC §5.6·§9.7 {@code change_decided}) — 승인·거절이
 * {@link src.backend.notification.domain.impl.SignupDecidedComposer} 와 같은 형태로 다른 문구를
 * 쓴다.
 *
 * <p>거절 문구에 사유를 싣는다 — {@code SignupDecidedComposer} 의 거절 문구와 같은 이유(사유가
 * 없으면 학부모가 무엇이 반려됐는지 알 수단이 없다). {@link ChangeAutoRejectedComposer}(자동 거절)가
 * 사유를 싣지 <b>않는</b> 것과 대비된다 — 그쪽은 사람이 판단해 거절한 것이 아니라 마감 도과라 실을
 * 사유 자체가 없다.
 *
 * <p>문구에 자녀 이름을 싣는다(ATT-03, R17-T3 목표 1) — 다자녀 가정이 같은 시각에 두 자녀의 결과를
 * 받으면 이름 없이는 구분할 수단이 없다({@link RiderStatusChangedComposer} 와 같은 근거).
 */
@Component
public class ChangeDecidedComposer implements NotificationComposer<ChangeDecidedSubject> {

    private static final String APPROVED_TITLE = "변경 요청 승인 안내";

    private static final String REJECTED_TITLE = "변경 요청 거절 안내";

    private static final String APPROVED_BODY = "%s 학생의 승하차 변경 요청이 승인되어 반영되었습니다.";

    private static final String REJECTED_BODY = "%s 학생의 승하차 변경 요청이 거절되었습니다. 사유: %s";

    @Override
    public NotificationMessage compose(ChangeDecidedSubject subject) {
        return subject.approved()
                ? new NotificationMessage(APPROVED_TITLE, APPROVED_BODY.formatted(subject.studentName()))
                : new NotificationMessage(REJECTED_TITLE,
                        REJECTED_BODY.formatted(subject.studentName(), subject.rejectReason()));
    }
}
