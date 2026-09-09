package src.backend.manager.dto;

import src.backend.global.common.enums.ManagerRole;

/**
 * 회차 확정 알림(route_changed, Phase 7 T3)의 수신자 조회 프로젝션 — {@link AssignedManagerView}
 * 와 같은 {@code assignment}·{@code manager} 조인이지만 <b>{@code accountId}</b> 를 추가로 싣는다.
 *
 * <p>기존 {@link AssignedManagerView} 를 확장하지 않고 새로 둔 이유는 그 DTO 가 이미
 * {@code RunQueryService}·{@code AssignmentCommandService} 두 곳의 응답 계약이라, 필드를 더하면
 * 그 응답에도 {@code accountId} 가 실린다 — 이 알림 발송 전용 조회이지 응답 계약이 아니다.
 */
public record AssignedManagerAccountView(Long managerId, Long accountId, String name, ManagerRole role) {
}
