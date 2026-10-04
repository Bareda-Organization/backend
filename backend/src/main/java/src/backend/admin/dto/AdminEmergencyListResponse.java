package src.backend.admin.dto;

import java.util.List;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 목록(목표 11, {@code GET /admin/emergencies}).
 *
 * <p>목록 필드명은 {@code items} 다(API_SPEC §6.11 — "§5.16 항목 + 아래", §5.16 {@code 응답 — items[]}).
 * BE-R1 목표 2 이전에는 {@code emergencies} 로 나갔다.
 *
 * <p>{@code counts} 는 {@code status} 쿼리만 뺀 같은 조건({@code academy_id})의 상태별 건수다 — {@code items} 의 200건 상한과
 * 무관하며, 화면 탭 3개의 숫자를 요청 한 번으로 그린다(Ruling 837).
 */
public record AdminEmergencyListResponse(List<AdminEmergencyItemResponse> items, long unackedCount, Counts counts) {

    /** 상태별 비상 알림 건수 — 상태 정의는 §5.16 과 같다({@code open} = 미확인 · 미취소). */
    public record Counts(long open, long acked, long canceled) {
    }
}
