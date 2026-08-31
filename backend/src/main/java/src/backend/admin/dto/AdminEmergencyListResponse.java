package src.backend.admin.dto;

import java.util.List;

/** 메인 관리자 콘솔의 전 학원 비상 알림 목록(목표 11, {@code GET /admin/emergencies}). */
public record AdminEmergencyListResponse(List<AdminEmergencyItemResponse> emergencies, long unackedCount) {
}
