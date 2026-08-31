package src.backend.exception.dto;

import java.time.OffsetDateTime;

/**
 * 미승차 연락 시도 기록 응답(API_SPEC §4.8) — 방금 남긴 시도 자체와, 그 시도가 케이스에 미친 결과를
 * 함께 싣는다. {@code resolvedAt} 이 채워지면 카운트다운이 멈춘 것이다({@code result=answered} 또는
 * {@code decision=depart}, {@link src.backend.exception.entity.NoShowCase} 참고) — 별도
 * {@code countdownStopped} 불리언을 두지 않고 이 필드 자체로 판단하게 한다. {@code null} 이면 아직
 * 대기 중이라는 뜻이라 별도 불리언과 항상 같은 정보이기 때문이다.
 */
public record NoShowContactResponse(Long caseId, String attemptType, String result, String decision,
        OffsetDateTime attemptedAt, OffsetDateTime resolvedAt) {
}
