package src.backend.admin.dto;

import java.util.List;

/** 끝나지 않은 이동 중 회차 목록(API_SPEC §6.16, Ruling 724) — 운행일 오름차순, 페이징 없음. */
public record StaleMovingRunListResponse(List<StaleMovingRunItemResponse> items) {
}
