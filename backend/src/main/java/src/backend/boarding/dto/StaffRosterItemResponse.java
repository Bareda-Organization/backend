package src.backend.boarding.dto;

/**
 * 관계자 웹의 호차별 일일 명단 1행(API_SPEC §5.4 {@code GET /staff/runs/{runId}/roster}, RST-03·A-04,
 * Phase 9 목표 6).
 *
 * <p>{@code guardianPhone} 이 원문이다 — 관계자 웹은 마스킹 대상 밖(§5.4·§1.12 L2)이라
 * {@code ManagerRosterResponse} 와 갈리는 지점이 이 필드 하나다. {@code absent} 학생도 행으로
 * 남는다({@code status=absent}) — 매니저 앱(행 제외)과 반대다.
 *
 * <p>{@code stopId}·{@code stopSeq} 는 그 승하차지의 정차 항목(run_stop) id·순번이다 — §5.19 노선 {@code stops[]} 와 id 로
 * 잇는다(Ruling 811). 확정 전(idle) 예정 명단은 정차 항목이 없어 {@code null} 이다.
 *
 * <p>{@code transferId} 는 확정 전 예정 명단에서 이동 대기(§5.8)로 이 회차에 들어온 행에만 실린다 —
 * §5.8.1 취소의 식별자다(Ruling 369). 강제 추가 행·확정 뒤 명단은 {@code null} 이다.
 */
public record StaffRosterItemResponse(Long studentId, String name, String className, String stopName, Long stopId,
        Integer stopSeq, String guardianPhone, String change, String status, String note, Long transferId) {
}
