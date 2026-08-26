package src.backend.account.event;

import java.time.OffsetDateTime;

import src.backend.global.common.enums.Role;

/**
 * 가입 요청이 수락·거절로 마감됐음을 알리는 도메인 이벤트(AUTH-10 · API_SPEC §5.2·§6.5) — 승인 축
 * 둘(관계자 · 메인 관리자)과 결과 둘(수락 · 거절), 즉 <b>결정 경로 4개 전부</b>가 이 하나를 발행한다.
 *
 * <p>이 타입이 발행측인 {@code account} 에 있는 이유는 <b>구독자가 여럿</b>이기 때문이다. 알림이
 * 유일한 구독자가 아니고(감사 로그 · 대시보드가 뒤따른다), 이벤트를 구독자 쪽에 두면 발행측이 구독자
 * 수만큼의 모듈을 알게 된다. 반대 방향(구독자가 발행측의 계약을 안다)은 §7 규칙 17 이 금지하는
 * 방향이 아니다 — 금지되는 것은 <b>발행측이 알림을 직접 부르는 것</b>이다.
 *
 * <p>{@code decidedAt} 을 싣는 이유는 소비자가 {@code dedup_key} 를 이 값으로 조립하기 때문이다.
 * 소비 시점의 시계로 조립하면 같은 이벤트가 두 번 배달됐을 때 키가 갈려 <b>중복 차단이 통째로
 * 무력해진다</b>.
 *
 * @param rejectReason 거절 사유. 수락이면 {@code null} 이다
 */
public record SignupDecidedEvent(Long academyId, Long accountId, String accountName, Role accountRole,
        boolean accepted, String rejectReason, OffsetDateTime decidedAt) {
}
