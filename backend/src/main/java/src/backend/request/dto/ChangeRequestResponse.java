package src.backend.request.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.request.entity.ChangeRequest;
import src.backend.run.entity.Run;

/**
 * 변경 신청 이력 한 건(API_SPEC §3.9 {@code GET /students/{id}/change-requests}).
 *
 * <p>{@code status} 는 4가지 값을 그대로 문자열로 실어 보낸다(대기·승인·거절·자동거절) — 목록 조회는
 * 판정을 가하지 않고 저장된 상태를 그대로 비춘다.
 *
 * @param serviceDate 대상 회차의 운행일 — 이력에 "오늘 하원" 처럼 쓴다(Ruling 824)
 * @param direction   대상 회차의 방향 {@code to_academy} · {@code from_academy}(Ruling 824)
 */
public record ChangeRequestResponse(Long changeRequestId, String type, String status, String rejectReason,
        Long runId, OffsetDateTime requestedAt, OffsetDateTime decidedAt, LocalDate serviceDate, String direction) {

    /** 신청 한 건과 그 대상 회차를 §3.9 이력 항목으로 옮긴다 — 운행일·방향은 신청이 아니라 회차의 값이다. */
    public static ChangeRequestResponse from(ChangeRequest changeRequest, Run run) {
        return new ChangeRequestResponse(changeRequest.getId(),
                changeRequest.getType().name().toLowerCase(Locale.ROOT),
                changeRequest.getStatus().name().toLowerCase(Locale.ROOT), changeRequest.getRejectReason(),
                changeRequest.getRunId(), changeRequest.getRequestedAt(), changeRequest.getDecidedAt(),
                run.getServiceDate(), run.getDirection().name().toLowerCase(Locale.ROOT));
    }
}
