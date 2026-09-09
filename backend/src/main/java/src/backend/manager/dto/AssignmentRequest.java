package src.backend.manager.dto;

/**
 * 회차별 매니저 배치 요청(MGR-05, API_SPEC §5.14 {@code PATCH /staff/runs/{runId}/assignment}).
 *
 * <p>둘 다 선택이나 <b>최소 하나는 필요</b>하다 — 둘 다 비면 {@code 422 VALIDATION_FAILED} 다. 빈
 * 요청을 200 으로 받으면 "아무것도 안 바꿨다" 와 "바꿨다" 가 응답에서 같아진다.
 *
 * <p>지정한 매니저의 {@code role} 이 그 자리와 어긋나면 {@code 404 MANAGER_NOT_FOUND} 다 —
 * {@code manager.role} 이 곧 앱 권한이라(C-06), 기사를 동승자 자리에 넣으면 그 계정이 승하차를 기록할
 * 수 있는지가 보는 곳마다 갈린다.
 *
 * <p><b>동승자 자동 배정은 이 경로가 아니다</b>(Phase 6) — 여기는 관계자가 손으로 지정하는 자리다.
 */
public record AssignmentRequest(Long driverManagerId, Long escortManagerId) {

    /** 바꿀 자리를 하나도 지정하지 않은 요청인가. */
    public boolean isEmpty() {
        return driverManagerId == null && escortManagerId == null;
    }
}
