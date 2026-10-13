package src.backend.exception.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 현장 예외 보고 등록 응답(API_SPEC §4.13). {@code replayed} 는 본문에 싣지 않고 컨트롤러가 상태 코드(최초 201 · 같은
 * {@code client_key} 재전송 200, §1.7)를 고르는 데만 쓴다.
 */
public record ExceptionReportCreateResponse(Long reportId, OffsetDateTime reportedAt, @JsonIgnore boolean replayed) {
}
