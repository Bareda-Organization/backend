package src.backend.run.dto;

import java.time.OffsetDateTime;

/** 노선 변경 확인 응답(API_SPEC §4.11) — 필드는 {@code acked_at} 하나뿐이다. */
public record RunAckChangesResponse(OffsetDateTime ackedAt) {
}
