package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.boarding.event.RiderStatusRevertedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 승하차 되돌리기 정정 알림(BRD-05, API_SPEC §4.7) 문구 — 목표 13·14(Ruling 219) 확정 정책대로 "승차
 * 취소"·"하차 취소" 를 서로 다른 문구로 낸다({@link RiderStatusChangedComposer} 와 나란한 형태).
 *
 * <p>이미 나간 승차·하차 알림을 고치거나 지우지 않고 이 정정 알림을 새로 적재하는 쪽으로 결정됐다
 * (Ruling 219) — 그래서 문구도 "취소됐습니다" 로, 원래 알림이 거짓이 됐다는 사실 자체를 알린다.
 */
@Component
public class RiderStatusRevertedComposer implements NotificationComposer<RiderStatusRevertedEvent> {

    private static final String TITLE = "승하차 정정 안내";
    private static final String BODY_BOARDING_CANCELED = "자녀의 승차 처리가 취소되었습니다.";
    private static final String BODY_ALIGHTING_CANCELED = "자녀의 하차 처리가 취소되었습니다.";

    @Override
    public NotificationMessage compose(RiderStatusRevertedEvent subject) {
        String body = switch (subject.canceledStatus()) {
            case "boarded" -> BODY_BOARDING_CANCELED;
            case "alighted" -> BODY_ALIGHTING_CANCELED;
            default -> throw new IllegalStateException("알 수 없는 취소 상태: " + subject.canceledStatus());
        };
        return new NotificationMessage(TITLE, body);
    }
}
