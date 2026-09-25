package src.backend.notification.domain.impl;

import src.backend.run.entity.DelayReason;

/**
 * 지연 알림 문구의 재료(NTF-06, API_SPEC §4.9) — {@link DelayComposer} 입력.
 *
 * <p>{@code message} 는 동승자가 프리셋을 고쳐 넣은 값이다 — 채워져 있으면 {@code reason} 기반
 * 자동 문구를 만들지 않고 그대로 본문으로 쓴다(발주문 판정 고정 — "message 있으면 그것을 본문으로").
 * {@code studentName} 은 학부모·학생 몫에만 채운다 — 두 자녀가 다른 버스를 타면 이름 없이는 어느 버스인지
 * 알 수 없다(ARCHITECTURE §11 · Ruling 225, BR-077).
 */
public record DelaySubject(DelayReason reason, int minutes, String message, String studentName) {

    /** 관계자 몫 — 회차 전체를 알리므로 특정 자녀가 없다. */
    public DelaySubject(DelayReason reason, int minutes, String message) {
        this(reason, minutes, message, null);
    }

    /** 같은 신고를 한 자녀 앞 문구로 옮긴다(학부모·학생 몫, ATT-03). */
    public DelaySubject forStudent(String name) {
        return new DelaySubject(reason, minutes, message, name);
    }
}
