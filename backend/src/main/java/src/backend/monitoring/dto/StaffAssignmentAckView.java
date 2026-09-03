package src.backend.monitoring.dto;

import src.backend.global.common.enums.ManagerRole;

/**
 * 회차 1건에 배치된 매니저 한 명의 이름 + 노선 확인 응답 판정 재료(§5.3 {@code driver_name}·
 * {@code escort_name}·{@code ack_driver}·{@code ack_escort}, RUN-07).
 *
 * <p>{@code ackedRouteVersionId}·{@code currentVersionId} 를 판정 결과(boolean)가 아니라 원재료로
 * 싣는다 — 판정 자체({@code assignment.acked_route_version_id} 가 {@code confirmed_route.
 * current_version_id} 와 같은가)는 {@code Assignment#ack}·{@code RunAckChangesCommandService} 가
 * 쓰는 값과 같은 두 컬럼을 비교하는 것뿐이라 여기서 boolean 으로 접으면 그 비교식이 이 레코드와
 * 소비 서비스 두 곳에 흩어진다.
 *
 * <p>{@code currentVersionId} 가 {@code null} 인 경우(노선 미확정) 판정은 항상 거짓이다 — 두 값이
 * 다 null 이어도 "확인함" 으로 셀 근거가 없다.
 */
public record StaffAssignmentAckView(Long runId, ManagerRole role, String name, Long ackedRouteVersionId,
        Long currentVersionId) {

    /** 이 배치가 현재 노선 버전을 확인했는지 — {@link src.backend.manager.entity.Assignment#ack} 가 쓰는 것과 같은 비교식. */
    public boolean acked() {
        return ackedRouteVersionId != null && ackedRouteVersionId.equals(currentVersionId);
    }
}
