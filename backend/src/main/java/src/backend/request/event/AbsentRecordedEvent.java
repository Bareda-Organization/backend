package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * 학생이 그 회차에 타지 않게 됐음을 알리는 도메인 이벤트 — {@code absent} 알림(API_SPEC §9.7, 관계자만)의 재료다.
 *
 * <p>발행 지점은 관계자 통지가 따로 없던 두 곳뿐이다 — ① 변경 신청 {@code cancel}(§3.8)과 ②구간 취소 승인(§5.6).
 * ① 탑승 토글 OFF 는 {@link IntentChangedEvent}({@code intent_changed})가 이미 관계자에게 알리므로 같은 사건을
 * 두 번 보내지 않는다(조율자 판정 2026-09-25, PRD "오늘 안 타요" — 관계자 통지 하나, BR-110).
 */
public record AbsentRecordedEvent(Long runId, Long academyId, Long studentId, OffsetDateTime recordedAt) {
}
