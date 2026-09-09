package src.backend.run.event;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.run.entity.DelayReason;

/**
 * 지연 알림 신고가 실제로 발신될 때 발행하는 도메인 이벤트(NTF-06, API_SPEC §4.9, F3 S1) —
 * 알림 모듈의 {@code DelayNotificationListener} 가 이 이벤트를 구독해
 * 아웃박스에 적재한다(ARCHITECTURE §3.3 규칙 17 · BRD-04 — 발행측이 알림 모듈을 직접 부르면 안 된다).
 *
 * <p>{@link RunStartedEvent} 와 같은 형태다 — 지연 신고 커맨드 서비스가 이미 연 트랜잭션 안에서
 * 발행되며, 그 트랜잭션이 롤백되면 이 이벤트도, 리스너가 적재한 행도 함께 롤백된다.
 *
 * <p>수신자 3집합({@code staffRecipients}·{@code guardianRecipients}·{@code studentRecipients})을
 * 이벤트가 직접 나르는 것은 {@code RunStartedEvent} 의 {@code autoBoardedCount} 와 같은 이유다 —
 * 커맨드 서비스가 응답의 세 불리언({@code notifiedGuardians} 등)을 <b>리스너 실행 결과에 기대지 않고</b>
 * 자신이 이미 계산해 둔 집합의 비어 있음 여부로 채워야 해서, 그 집합을 다시 조회하지 않고 그대로
 * threading 한다.
 */
public record DelayRequestedEvent(Long runId, Long academyId, int minutes, DelayReason reason, String message,
        OffsetDateTime sentAt, List<DelayNoticeRecipient> staffRecipients,
        List<DelayNoticeRecipient> guardianRecipients, List<DelayNoticeRecipient> studentRecipients) {
}
