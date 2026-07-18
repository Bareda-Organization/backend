package src.backend.notification.sender;

import src.backend.notification.entity.NotificationLog;

/**
 * 알림 실제 발송 포트. {@code LocationSource}(위치 소스 추상화)와 같은 발상 —
 * "무엇을 보낼지"(NotificationService)와 "어떻게 전달하는지"(이 인터페이스)를 분리해,
 * MVP 의 로그 출력을 나중에 FCM 푸시·카카오 알림톡 구현체로 교체해도 호출부는 그대로 둔다.
 */
public interface NotificationSender {

    void send(NotificationLog notification);
}
