package src.backend.location.event;

/**
 * 회차의 결석이 아닌 탑승 학생 목록이 바뀌었음을 알린다 — {@code boarding} 이 {@code run_rider} 의 결석 전이 · 최초 생성을 커밋할 때
 * 발행하고, {@code location} 이 위치 방송 수신자 캐시를 비운다(RT-5, Ruling 899 K).
 */
public record RunRidersChangedEvent(Long runId) {
}
