package src.backend.notification.domain.spec;

/**
 * 알림 한 건의 제목·본문 — {@link NotificationComposer} 가 만들어 아웃박스 적재로 넘긴다.
 *
 * <p>{@code notification_log} 의 {@code title}·{@code body} 두 컬럼과 1:1 이다. 두 값을 따로 돌려주는
 * 대신 한 타입으로 묶은 이유는 문구가 <b>함께</b> 바뀌기 때문이다 — 제목만 바꾸고 본문을 두면 두
 * 문장이 서로 다른 결정을 설명하게 된다.
 */
public record NotificationMessage(String title, String body) {
}
