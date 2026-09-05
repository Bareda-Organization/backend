package src.backend.admin.dto;

import java.time.OffsetDateTime;

/**
 * 강제 확정 콘솔 개입 응답(API_SPEC §6.14, F3 S2 목표 11) — {@code fallbackUsed} 는
 * <b>항상 {@code true}</b> 다. 이 경로가 저장에 성공했다는 것 자체가 강제 폴백 계산이 이겼다는
 * 뜻이라({@code RunConfirmationService#confirmOne(Long, boolean)} 의 경쟁 판정), 실제 계산 결과를
 * 다시 조회해 확인할 필요가 없다.
 */
public record ForceConfirmResponse(Long runId, Long routeVersionId, boolean fallbackUsed,
        OffsetDateTime confirmedAt) {
}
