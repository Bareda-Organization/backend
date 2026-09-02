package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 비상 신고 접수 알림의 문구(EXC-04, Phase 11 T2 목표 6) — 수신자는 학원 관계자·메인관리자 둘이지만
 * {@link src.backend.notification.domain.impl.RunStartedComposer} 와 같은 이유로 문구는 공통이다.
 *
 * <p>{@code type} 을 한글로 옮겨 싣는다 — {@code busNo} 만으로는 무슨 상황인지 알림함에서 알 수
 * 없어 관계자가 앱을 열어보기 전까지 심각도를 가늠할 수 없다.
 */
@Component
public class EmergencyRaisedComposer implements NotificationComposer<EmergencyRaisedEvent> {

    private static final String TITLE = "비상 상황 발생";

    @Override
    public NotificationMessage compose(EmergencyRaisedEvent subject) {
        String body = "%s 차량에서 %s 상황이 발생했습니다. 즉시 확인해 주세요."
                .formatted(subject.busNo(), label(subject.type()));
        return new NotificationMessage(TITLE, body);
    }

    private String label(EmergencyType type) {
        return switch (type) {
            case ACCIDENT -> "사고";
            case VEHICLE_FAULT -> "차량 고장";
            case STUDENT_EMERGENCY -> "학생 응급";
            case ETC -> "기타 비상";
        };
    }
}
