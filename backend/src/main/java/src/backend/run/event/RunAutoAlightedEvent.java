package src.backend.run.event;

import java.time.OffsetDateTime;

/**
 * 등원 회차 최종 도착 처리에서 <b>서버가 자동으로</b> 하차 처리한 학생 1명을 알리는 도메인
 * 이벤트(API_SPEC §4.5·§9.7 {@code alighting}, C-07·C-15) — 학생 1명당 1건 발행한다.
 *
 * <p>학생 단위로 나누는 이유는 알림도 학생당 1행이기 때문이다(Phase 9 goal 9 — "알림 행 수 = 자동
 * 하차 인원 수"). 회차 단위로 묶어 하나만 발행하면 그 이벤트를 처리하는 쪽이 다시 학생 목록을
 * 순회해야 하고, 그 순회에서 한 명이 빠져도 발행 건수만으로는 드러나지 않는다.
 *
 * <p>동승자의 개별 하차 처리(§4.6)가 만드는 알림과 <b>수신자·알림 종류는 같지만</b>(둘 다
 * 보호자 1명, {@code NotificationType.ALIGHTING}) 발행 이벤트는 다르다 — 저쪽은 사람이 누른
 * 조작이고 이쪽은 서버가 최종 지점 도착과 함께 일괄 처리한 결과라, 같은 이벤트로 묶으면 "누가
 * 하차 처리를 했는가" 라는 사실이 이벤트 밖(호출 스택)에만 남는다.
 */
public record RunAutoAlightedEvent(Long runId, Long academyId, Long studentId, OffsetDateTime alightedAt) {
}
