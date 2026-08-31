package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.boarding.event.RiderNoShowEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 미승차 학부모 알림(BRD-04, API_SPEC §4.6 {@code no_show} 행 "즉시 발송") 문구.
 *
 * <p>{@link NoShowStaffComposer} 와 재료 타입({@link RiderNoShowEvent})이 같아 둘 다
 * {@code NotificationComposer<RiderNoShowEvent>} 빈이다 — 필드명이 빈 이름과 일치하는 스프링의
 * 기본 해석으로 주입처가 갈린다({@code IntentNotificationListener} 의 필드명 일치 관례와 같다).
 */
@Component
public class NoShowParentComposer implements NotificationComposer<RiderNoShowEvent> {

    private static final String TITLE = "미승차 안내";
    private static final String BODY = "자녀가 아직 버스에 탑승하지 않았습니다. 확인해 주세요.";

    @Override
    public NotificationMessage compose(RiderNoShowEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
