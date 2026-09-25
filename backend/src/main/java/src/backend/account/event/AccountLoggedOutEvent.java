package src.backend.account.event;

import java.time.OffsetDateTime;

/**
 * 한 기기에서 로그아웃했음을 알리는 도메인 이벤트(API_SPEC §2.7 · §2.11, Ruling 331) — 알림 모듈이 구독해
 * 그 기기의 푸시 단말 토큰을 해지한다.
 *
 * <p>account 가 notification 저장소를 직접 부르지 않고 이벤트를 던지는 이유는 모듈 경계 규칙이다
 * ({@code NotificationModuleIsolationTest} — 발행측은 도메인 이벤트만 던진다). 구독자는 평범한
 * {@code @EventListener} 라 로그아웃 트랜잭션 안에서 함께 반영된다.
 *
 * @param deviceId 로그아웃한 기기 — 요청에 없으면 이벤트를 발행하지 않는다
 */
public record AccountLoggedOutEvent(Long accountId, String deviceId, OffsetDateTime loggedOutAt) {
}
