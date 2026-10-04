package src.backend.exception.dto;

import java.util.List;

/**
 * 예외 보고 목록(API_SPEC §5.20 목록) — 페이지네이션이 없고 최근 200건까지만 돌려준다. 최상위 {@code counts} 는
 * {@code handled} 쿼리만 뺀 같은 조건의 처리·미처리 건수다(Ruling 814 — 상한과 무관).
 */
public record StaffReportListResponse(List<StaffReportItemResponse> items, Counts counts) {

    /** 처리·미처리 건수 한 쌍. */
    public record Counts(long handled, long unhandled) {
    }

    /** 목록 항목과 처리·미처리 건수를 한 응답으로 묶는다. */
    public static StaffReportListResponse of(List<StaffReportItemResponse> items, Counts counts) {
        return new StaffReportListResponse(items, counts);
    }
}
