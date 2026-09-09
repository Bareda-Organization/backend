package src.backend.manager.dto;

import java.util.List;

/**
 * 배치 결과(MGR-05·06, API_SPEC §5.14).
 *
 * @param assignments 이번 요청이 바꾼 것만이 아니라 <b>그 회차의 현재 배치 전부</b> — 기사만 바꾼
 *                    요청이 동승자를 지운 것처럼 보이지 않게 한다
 * @param warnings    충돌이 없으면 <b>빈 배열</b>이다. 항상 무언가를 담는 구현과 구별되어야 한다(목표 3)
 */
public record RunAssignmentResponse(Long runId, List<AssignedManagerResponse> assignments,
        List<AssignmentWarning> warnings) {
}
