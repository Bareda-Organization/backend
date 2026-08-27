package src.backend.manager.dto;

import src.backend.global.common.enums.ManagerRole;

/**
 * 배치 조회의 프로젝션 — {@code assignment} 와 {@code manager} 를 조인해 뽑은 한 줄이다.
 *
 * <p>엔티티가 아니라 프로젝션으로 받는 이유는 회차 목록이 배치를 <b>회차마다</b> 필요로 하기
 * 때문이다(횡단 규칙 4: 무거운 조회는 DTO 프로젝션) — 엔티티로 받으면 이름을 얻으려고 매니저를
 * 행마다 다시 조회한다.
 *
 * <p>{@code runId} 를 담는 것이 요점이다 — 여러 회차의 배치를 한 번에 읽으므로 어느 회차의 것인지
 * 없이는 되돌려 나눌 수단이 부재하다.
 */
public record AssignedManagerView(Long runId, Long managerId, String name, ManagerRole role) {

    /** 응답 항목으로 옮긴다 — {@code runId} 는 이미 상위 회차가 들고 있어 싣지 않는다. */
    public AssignedManagerResponse toResponse() {
        return AssignedManagerResponse.of(managerId, name, role);
    }
}
