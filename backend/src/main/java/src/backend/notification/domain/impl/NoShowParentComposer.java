package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.global.common.enums.Direction;
import src.backend.notification.domain.spec.NoShowParentSubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 미승차 학부모 알림(BRD-04) 문구 — 문구에 자녀 이름을 싣는다(ATT-03, P-02, Ruling 225). 관계자 몫은
 * {@link NoShowStaffComposer} 가 별도로 맡고, 그쪽은 이름을 넣지 않는다(§9.7 수신자 열 — ATT-03 은
 * 학부모 수신에만 걸린다).
 */
@Component
public class NoShowParentComposer implements NotificationComposer<NoShowParentSubject> {

    private static final String TITLE = "미승차 안내";
    private static final String BODY = "%s 학생이 아직 버스에 탑승하지 않았습니다. 확인해 주세요.";
    /** 하원의 {@code no_show} 는 종료 보류 정리로만 생겨 버스에 탔던 학생이다(Ruling 881, FEATURE_SPEC §3.3). */
    private static final String FROM_ACADEMY_BODY = "%s 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.";

    @Override
    public NotificationMessage compose(NoShowParentSubject subject) {
        String body = subject.direction() == Direction.FROM_ACADEMY ? FROM_ACADEMY_BODY : BODY;
        return new NotificationMessage(TITLE, body.formatted(subject.studentName()));
    }
}
