package src.backend.admin.dto;

import java.time.OffsetDateTime;

/** 강제 종료 응답(API_SPEC §6.17) — {@code boardedCount} 는 하차 처리 없이 남겨진 탑승자 수다(감사 기록과 같은 값). */
public record ForceFinishResponse(Long runId, OffsetDateTime finishedAt, long boardedCount) {
}
