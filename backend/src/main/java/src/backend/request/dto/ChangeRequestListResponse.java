package src.backend.request.dto;

import java.util.List;

/**
 * 변경 신청 이력 목록(API_SPEC §3.9) — {@code pendingCount} 는 화면이 "대기 중 N건"을 다시 세지 않도록
 * 서버가 미리 집계해 함께 보낸다.
 */
public record ChangeRequestListResponse(List<ChangeRequestResponse> items, long pendingCount) {
}
