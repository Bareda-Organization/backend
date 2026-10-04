package src.backend.manager.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 매니저 목록 응답(API_SPEC §5.13) — 공통 쪽 봉투(§1.8)에 최상위 {@code counts} 를 더한다(Ruling 817). {@code counts} 는 재직
 * 매니저 중 오늘 배치 유무별 수이고 쿼리·쪽과 무관하다.
 */
public record ManagerListResponse(List<ManagerResponse> items, int page, int size, long totalCount,
        boolean hasNext, Counts counts) {

    /** 오늘 미취소 회차에 배치된 매니저 수와 배치되지 않은 매니저 수. */
    public record Counts(long assignedToday, long unassignedToday) {
    }

    /** 공통 쪽 봉투를 그대로 옮기고 건수만 붙인다. */
    public static ManagerListResponse of(PageResponse<ManagerResponse> page, Counts counts) {
        return new ManagerListResponse(page.items(), page.page(), page.size(), page.totalCount(), page.hasNext(),
                counts);
    }
}
