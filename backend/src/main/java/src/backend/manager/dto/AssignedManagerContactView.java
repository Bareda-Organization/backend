package src.backend.manager.dto;

import src.backend.global.common.enums.ManagerRole;

/**
 * §6.8 메인 관리자 관제(목표 8)가 쓰는 배치 조회 프로젝션 — {@link AssignedManagerView} 에 원문
 * {@code phone} 을 더한 것과 같은 모양이다.
 *
 * <p>별도 레코드로 두는 이유는 {@link AssignedManagerView} 가 §5.10·§5.14 응답 조립에 이미 쓰이고
 * 있어 필드를 늘리면 그쪽 호출부까지 영향받기 때문이다 — 전화번호가 필요 없는 기존 자리에도
 * {@code manager.phone} 을 매번 읽어 오는 낭비가 더해진다.
 */
public record AssignedManagerContactView(Long runId, Long managerId, String name, String phone, ManagerRole role) {
}
