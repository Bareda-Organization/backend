package src.backend.routing.dto;

import java.time.OffsetDateTime;

/**
 * 회차 여러 개의 현재 정차지 이름을 한 번에 구하려고 읽는 정차 항목 투영 — 어느 회차인지({@code runId}) · 순서 · 도착 시각 · 정차 이름.
 * 이름은 승하차지면 승하차지 이름, 경유지면 경유지 라벨, 도착지(학원) 항목이면 {@code null}({@link PositionStopView} 와 같다).
 */
public record RunStopNameView(Long runId, int seq, OffsetDateTime arrivedAt, String name) {
}
