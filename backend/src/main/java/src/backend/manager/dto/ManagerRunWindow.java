package src.backend.manager.dto;

import java.time.OffsetDateTime;

/**
 * 매니저가 배치된 다른 회차의 시간 창 후보 — {@code MANAGER_DOUBLE_BOOKED} 구간 겹침 판정
 * (Ruling 193, Phase 8 목표 13)이 이 결과를 받아 겹침을 가른다.
 *
 * <p>{@code estDurationMin} 을 접지 않고 그대로 담는다 — {@code null} 일 때 점으로 접는 규칙이
 * {@code AssignmentConflictDetector#windowEnd} 에 있는 도메인 판단이라, 저장소가 미리 접으면 같은
 * 규칙이 두 곳에 흩어져 하나만 바뀌었을 때 아무도 못 알아챈다.
 */
public record ManagerRunWindow(OffsetDateTime departTime, Integer estDurationMin) {
}
