package src.backend.notification.domain.impl;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 비상 알림(EXC-04) 문구 — 고정 문구 표({@link NotificationTexts})와 같은 규칙(글자는 {@code NotificationTextGoldenTest} 가 고정)을 따르되, 수신자가 관계자와
 * 메인 관리자라는 점이 달라 따로 둔다.
 */
@Configuration
public class EmergencyNotificationTexts {

    /** 비상 신고 접수 — 수신자 관계자·메인관리자 공통. 상황 유형을 한글로 싣는다(차량 번호만으로는 심각도를 가늠할 수 없다). */
    @Bean
    public NotificationComposer<EmergencyRaisedEvent> emergencyRaisedComposer() {
        return event -> new NotificationMessage("비상 상황 발생",
                "%s 차량에서 %s 상황이 발생했습니다. 즉시 확인해 주세요.".formatted(event.busNo(), label(event.type())));
    }

    /** 비상 신고 취소. */
    @Bean
    public NotificationComposer<EmergencyCanceledEvent> emergencyCanceledComposer() {
        return event -> new NotificationMessage("비상 신고 취소", "%s 차량의 비상 신고가 취소되었습니다.".formatted(event.busNo()));
    }

    private static String label(EmergencyType type) {
        return switch (type) {
            case ACCIDENT -> "사고";
            case VEHICLE_FAULT -> "차량 고장";
            case STUDENT_EMERGENCY -> "학생 응급";
            case ETC -> "기타 비상";
        };
    }
}
