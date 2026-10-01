package src.backend.routing.dto;

import java.time.OffsetDateTime;

/**
 * 위치 수신이 확정 노선의 정차 항목마다 필요한 값만 담은 프로젝션 — 순서({@code seq}) · 도착 시각 · 계획 도착 시각({@code eta}) ·
 * 정차 이름. 엔티티가 아니라 프로젝션인 이유는 위치 1건(2초마다)이 정차 목록과 정차 이름을 한 문장으로 읽어 SQL 을 줄이기 위해서다
 * (R46-LATERBE L2). {@code name} 은 승하차지면 승하차지 이름, 경유지면 경유지 라벨, 도착지(학원) 항목이면 {@code null}.
 */
public record PositionStopView(int seq, OffsetDateTime arrivedAt, OffsetDateTime eta, String name) {
}
