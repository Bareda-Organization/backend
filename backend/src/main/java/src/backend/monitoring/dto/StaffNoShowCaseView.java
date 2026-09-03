package src.backend.monitoring.dto;

import java.time.OffsetDateTime;

/**
 * 회차 1건에 걸린 진행 중 미승차 에스컬레이션 케이스 한 줄(§5.3 {@code runs[].no_show_cases[]},
 * BRD-05).
 *
 * <p>{@code resolvedAt IS NULL} 로 이미 좁혀 읽는다 — 대시보드는 "지금 대기 중인 것" 만 보여줘야
 * 하고, 해소된 케이스까지 실으면 관계자가 이미 끝난 건을 다시 처리하려 든다.
 */
public record StaffNoShowCaseView(Long runId, String studentName, String stopName, OffsetDateTime expiresAt) {
}
