package src.backend.boarding.dto;

import java.time.OffsetDateTime;

/**
 * 승하차 처리 응답(API_SPEC §4.6) — {@code rider_id} 는 문서 예시가 {@code "rider_5521"} 처럼 문자열
 * 접두를 쓰지만, {@link src.backend.request.dto.BoardingIntentToggleResponse} 가 이미 정리한 관례대로
 * 순수 {@code Long} 으로 노출한다.
 *
 * <p>{@code noShowCase} 는 {@code status=no_show} 일 때만 채운다 — 그 외 상태는 {@code null} 이라
 * 응답 JSON 에서 {@code no_show_case} 필드 자체가 빠진다(Jackson 기본 동작).
 */
public record RiderStatusUpdateResponse(Long riderId, String status, OffsetDateTime changedAt,
        NoShowCaseSummary noShowCase, boolean stopSkipped) {

    /** {@code no_show_case} 를 채우지 않는 승차·하차 응답. */
    public static RiderStatusUpdateResponse of(Long riderId, String status, OffsetDateTime changedAt,
            boolean stopSkipped) {
        return new RiderStatusUpdateResponse(riderId, status, changedAt, null, stopSkipped);
    }

    /** 미승차 응답 — {@link NoShowCaseSummary} 를 함께 싣는다(목표 7). */
    public static RiderStatusUpdateResponse withNoShowCase(Long riderId, OffsetDateTime changedAt,
            NoShowCaseSummary noShowCase, boolean stopSkipped) {
        return new RiderStatusUpdateResponse(riderId, "no_show", changedAt, noShowCase, stopSkipped);
    }

    /** {@code case_id} · {@code started_at} · {@code expires_at}(3분 후, API_SPEC §4.6 표) 3필드. */
    public record NoShowCaseSummary(Long caseId, OffsetDateTime startedAt, OffsetDateTime expiresAt) {
    }
}
