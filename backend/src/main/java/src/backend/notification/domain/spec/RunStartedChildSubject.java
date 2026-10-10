package src.backend.notification.domain.spec;

/**
 * 학부모·학생이 받는 운행 시작 알림 문구의 재료(Ruling 868) — {@code RunStartedChildComposer} 입력.
 * 관계자 몫은 그대로 {@code RunStartedEvent} 를 받는다 — 자녀 이름 규칙(ATT-03)은 학부모·학생 수신에만 걸린다.
 */
public record RunStartedChildSubject(String studentName) {
}
