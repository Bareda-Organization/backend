package src.backend.exception.dto;

import java.util.List;

/**
 * 학원 관계자 화면의 비상 알림 목록(목표 10) — {@code unackedCount} 는 저장 컬럼이 아니라 목록에서
 * {@code acked == false && canceledAt == null} 인 행을 세어 응답 시점에 계산한다(취소된 신고는
 * 확인 대기로 세지 않는다 — 이미 물린 신고를 계속 미확인으로 보이면 관계자가 헛수고로 확인을 누른다).
 */
public record EmergencyStaffListResponse(List<EmergencyStaffItemResponse> emergencies, long unackedCount) {
}
