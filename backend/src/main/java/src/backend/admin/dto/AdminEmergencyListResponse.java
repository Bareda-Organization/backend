package src.backend.admin.dto;

import java.util.List;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 목록(목표 11, {@code GET /admin/emergencies}).
 *
 * <p>목록 필드명은 {@code items} 다(API_SPEC §6.11 — "§5.16 항목 + 아래", §5.16 {@code 응답 — items[]}).
 * BE-R1 목표 2 이전에는 {@code emergencies} 로 나갔다.
 */
public record AdminEmergencyListResponse(List<AdminEmergencyItemResponse> items, long unackedCount) {
}
