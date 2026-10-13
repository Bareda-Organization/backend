package src.backend.manager.event;

import java.time.OffsetDateTime;

/**
 * 계정이 연결된 매니저가 삭제되었음을 알리는 도메인 이벤트(MGR-04 · Ruling 891) — {@code account} 모듈이 구독해
 * 그 계정의 유효 refresh 토큰을 전량 무효화하고, {@code notification} 모듈이 푸시 단말을 지운다.
 *
 * <p>직접 호출이 아니라 이벤트인 이유는 {@link ManagerRoleChangedEvent} 와 같다 — 모듈 방향(발행측이 알림·계정 저장소를 모른다).
 * 구독자는 삭제 트랜잭션 안에서 처리한다 — 삭제만 커밋되고 토큰·단말이 남으면 삭제된 매니저의 폰으로 푸시가 가고 세션이 이어진다.
 * 연결된 계정이 없는 매니저는 이벤트를 발행하지 않는다.
 */
public record ManagerDeletedEvent(Long accountId, OffsetDateTime deletedAt) {
}
