package src.backend.exception.dto;

import java.time.OffsetDateTime;

/** 현장 예외 보고 등록 응답(API_SPEC §4.13). */
public record ExceptionReportCreateResponse(Long reportId, OffsetDateTime reportedAt) {
}
