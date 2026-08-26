package src.backend.notification.push.spec;

/**
 * 단말 푸시 발송 포트(§7 규칙 12 교체 축) — FCM · APNs · 알림톡이 서로 다른 채널이라 호출부는 이
 * 인터페이스만 안다.
 *
 * <p>구현체 선택은 {@code app.push.sender} 한 곳이 정한다(ARCHITECTURE §3.2.1) — 호출부에 분기를
 * 두면 채널 교체가 전수 수정이 된다.
 */
public interface PushSender {

    /**
     * 한 건을 대상 계정의 단말로 보낸다.
     *
     * <p>성공을 반환값이 아니라 <b>예외 부재</b>로 표현한다 — {@code boolean} 을 돌려주면 호출부가
     * 그 값을 무시해도 컴파일되고, 그 순간 실패가 성공으로 기록된다.
     *
     * @throws RuntimeException 발송 실패. 호출부가 잡아 {@code fail_reason} 에 옮기고 행을
     *                          {@code pending} 으로 남긴다(TECH_DECISIONS §7.2)
     */
    void send(PushMessage message);
}
