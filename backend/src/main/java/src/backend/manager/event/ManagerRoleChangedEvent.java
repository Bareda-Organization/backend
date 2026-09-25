package src.backend.manager.event;

import src.backend.global.common.enums.ManagerRole;

/**
 * 계정이 연결된 매니저의 역할이 바뀌었음을 알리는 도메인 이벤트(MGR-03 · API_SPEC §5.13) — {@code account}
 * 모듈이 구독해 계정 역할(토큰 {@code role} 클레임의 출처)을 맞춘다.
 *
 * <p>직접 호출이 아니라 이벤트인 이유는 모듈 방향이다 — {@code account} 가 이미 {@code manager} 를 안다
 * (가입 승인의 계정 연결). 구독자는 발행 트랜잭션 안에서 처리해야 한다 — 매니저 역할만 커밋되고 계정
 * 역할이 남으면 이 이벤트가 고치려는 어긋남이 그대로 남는다.
 */
public record ManagerRoleChangedEvent(Long accountId, ManagerRole role) {
}
