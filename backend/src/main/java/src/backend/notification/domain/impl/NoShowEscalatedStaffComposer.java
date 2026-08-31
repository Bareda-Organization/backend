package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 미승차 에스컬레이션 관계자 알림(목표 1, API_SPEC §4.8 "3분 경과 + 무응답이면 관계자 에스컬레이션
 * 보고") 문구 — {@link NoShowStaffComposer} 와 재료가 다르다({@code NoShowEscalatedEvent} 는 대기가
 * 만료된 뒤에만 발행돼, "발생" 이 아니라 "무응답 지속" 을 알린다). 수신자는 관계자뿐이다 — §4.8 이
 * 학부모 앞 알림을 다시 언급하지 않는다(연락은 이미 학부모에게 직접 시도된 뒤라 별도 알림이 아니라
 * 관계자 보고만 남는다는 판단).
 */
@Component
public class NoShowEscalatedStaffComposer implements NotificationComposer<NoShowEscalatedEvent> {

    private static final String TITLE = "미승차 에스컬레이션";
    private static final String BODY = "미승차 대기 시간이 지났는데도 응답이 없습니다. 확인해 주세요.";

    @Override
    public NotificationMessage compose(NoShowEscalatedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}
