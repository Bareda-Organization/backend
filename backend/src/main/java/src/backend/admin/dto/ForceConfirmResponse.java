package src.backend.admin.dto;

import java.time.OffsetDateTime;

/**
 * 강제 확정 콘솔 개입 응답(API_SPEC §6.14, F3 S2 목표 11) — {@code fallbackUsed} 는 <b>스펙상 항상
 * {@code true}</b> 다. 값 자체는 미리 단정하지 않고 저장된 {@code route_version.fallback_used} 를
 * 그대로 실어({@code RunForceConfirmCommandService}) 강제 폴백 호출이 조용히 빠지는 결함이 있으면
 * 이 필드가 {@code false} 로 드러나게 한다.
 */
public record ForceConfirmResponse(Long runId, Long routeVersionId, boolean fallbackUsed,
        OffsetDateTime confirmedAt) {
}
