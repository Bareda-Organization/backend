package src.backend.student.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 관계자 학생 목록 응답(API_SPEC §5.11) — 공통 쪽 봉투(§1.8)에 학원 전체 지표 {@code summary} 를 최상위로 더한다(Ruling 815).
 * {@code summary} 는 쿼리·쪽과 무관한 값이라 지표 칸이 필터를 걸어도 바뀌지 않는다.
 */
public record StudentListResponse(List<StudentSummaryResponse> items, int page, int size, long totalCount,
        boolean hasNext, Summary summary) {

    /** 학원 전체 재원 학생 지표 — 총원 · 반 종류 수 · 보호자 미연결 · 주소 미등록 · 혼자 귀가 가능. */
    public record Summary(long total, long classCount, long guardianUnlinked, long addressMissing,
            long canGoAlone) {
    }

    /** 공통 쪽 봉투를 그대로 옮기고 지표만 붙인다. */
    public static StudentListResponse of(PageResponse<StudentSummaryResponse> page, Summary summary) {
        return new StudentListResponse(page.items(), page.page(), page.size(), page.totalCount(), page.hasNext(),
                summary);
    }
}
