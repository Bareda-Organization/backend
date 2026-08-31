package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.boarding.event.RiderNoShowEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 미승차 관계자 알림(BRD-04, API_SPEC §4.6 {@code no_show} 행 "미승차 카운트 +1") 문구 —
 * {@link NoShowParentComposer} 와 재료가 같지만 수신자가 달라 문구를 따로 둔다. 에스컬레이션
 * 시작(같은 행의 나머지 서술)은 Phase 11 이라 여기서 다루지 않는다.
 */
@Component
public class NoShowStaffComposer implements NotificationComposer<RiderNoShowEvent> {

    private static final String TITLE = "미승차 발생";
    private static final String BODY = "학생 한 명이 미승차로 확인됐습니다. 명단에서 확인해 주세요.";

    @Override
    public NotificationMessage compose(RiderNoShowEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
