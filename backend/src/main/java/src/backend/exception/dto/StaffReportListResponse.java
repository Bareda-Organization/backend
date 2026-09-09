package src.backend.exception.dto;

import java.util.List;

/**
 * 예외 보고 목록(API_SPEC §5.20 목록) — 페이지네이션이 없다. §5.20 은 요청 쿼리를 {@code type} ·
 * {@code date} · {@code run_id} 셋만 명시하고 "페이징" 을 적지 않는다 — 같은 형태로 페이징이 없는
 * {@code /staff/emergencies}(§5.16, 쿼리 {@code status} · {@code date})를 따른다.
 * {@code /staff/notifications}(§5.17)는 쿼리에 "페이징" 을 명시로 적어 두 형제와 갈리므로 본보기로
 * 삼지 않는다.
 */
public record StaffReportListResponse(List<StaffReportItemResponse> items) {

    public static StaffReportListResponse of(List<StaffReportItemResponse> items) {
        return new StaffReportListResponse(items);
    }
}
