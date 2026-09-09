package src.backend.exception.dto;

import java.util.List;

/** 발신자가 자기 회차의 비상 신고 처리 상태를 조회하는 목록(§4.15). */
public record RunEmergencyListResponse(List<RunEmergencyItemResponse> items) {
}
