package src.backend.request.event;

import java.time.OffsetDateTime;

/**
 * 변경 요청이 서버에 의해 자동 거절됐음을 알리는 도메인 이벤트(API_SPEC §1.6) — {@code change_decided}
 * 알림(신청 학부모 앞)이 이 이벤트를 구독한다.
 *
 * <p>{@link src.backend.run.event.RunRouteConfirmedEvent} 와 같은 형태의 평범한 record 다 —
 * {@code ChangeRequestAutoRejectionPersistence.autoRejectOne} 이 조건부 UPDATE 로 갱신에
 * 성공한 트랜잭션 안에서만 발행한다. 이름·역할을 값으로 담지 않는 이유는
 * {@link src.backend.account.event.SignupDecidedEvent} 와 다르게 이쪽 발행측(자동 거절
 * 배치·moving 종결 서비스)은 신청자 계정을 조회할 이유가 이미 없기 때문이다 — 구독자
 * ({@code ChangeRequestAutoRejectedNotificationListener})가 {@code requestedBy} 로 계정을
 * 직접 찾는다.
 */
public record ChangeRequestAutoRejectedEvent(Long academyId, Long changeRequestId, Long runId, Long studentId,
        Long requestedBy, OffsetDateTime decidedAt) {
}
