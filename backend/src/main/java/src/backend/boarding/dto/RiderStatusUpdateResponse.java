package src.backend.boarding.dto;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.exception.dto.NoShowContactView;

/**
 * 승하차 처리 응답(API_SPEC §4.6) — {@code rider_id}·{@code case_id} 필드 타입은 {@code Long} 이지만
 * JSON 으로는 {@link src.backend.global.config.IdentifierJsonConfig} 가 문자열로 감싸 내보낸다
 * (API_SPEC §1.1, Ruling 332). 문서 예시의 {@code "rider_5521"} 접두는 표기 관례일 뿐 실제 값은
 * 순번 그대로다.
 *
 * <p>{@code noShowCase} 는 {@code status=no_show} 일 때만 채운다 — 그 외 상태는 {@code null} 이고,
 * {@code application.yml}에 {@code default-property-inclusion} 설정이 없어 응답 JSON 에도
 * {@code "no_show_case": null} 로 그대로 실린다(필드 자체가 빠지지 않는다).
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

    /**
     * {@code case_id} · {@code started_at} · {@code expires_at}(3분 후, API_SPEC §4.6 표) 3필드와 연락 이력 {@code contacts}(§4.2 명단의 같은 객체 —
     * 되돌렸다 다시 미승차가 된 케이스는 이전 시도를 갖는다, Ruling 823).
     */
    public record NoShowCaseSummary(Long caseId, OffsetDateTime startedAt, OffsetDateTime expiresAt,
            List<NoShowContactView> contacts) {
    }
}
