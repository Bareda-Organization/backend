package src.backend.notification.domain.impl;

import src.backend.run.entity.DelayReason;

/**
 * 지연 알림 문구의 재료(NTF-06, API_SPEC §4.9) — {@link DelayComposer} 입력.
 *
 * <p>{@code message} 는 동승자가 프리셋을 고쳐 넣은 값이다 — 채워져 있으면 {@code reason} 기반
 * 자동 문구를 만들지 않고 그대로 본문으로 쓴다(발주문 판정 고정 — "message 있으면 그것을 본문으로").
 */
public record DelaySubject(DelayReason reason, int minutes, String message) {
}
