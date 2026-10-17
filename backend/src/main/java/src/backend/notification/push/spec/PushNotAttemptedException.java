package src.backend.notification.push.spec;

/** 발송기가 채널(FCM 등)을 부르지 않고 거절했다는 신호 — 시도가 아니므로 호출부는 시도 횟수를 올리지 않는다(Ruling 899 E). */
public class PushNotAttemptedException extends RuntimeException {

    public PushNotAttemptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
