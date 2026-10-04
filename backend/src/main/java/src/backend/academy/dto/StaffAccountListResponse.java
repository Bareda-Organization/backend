package src.backend.academy.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 관계자 계정 목록 응답(API_SPEC §6.6) — §1.8 페이징 봉투에 최상위 {@code counts} 를 더한 형태다. {@link PageResponse} 를 품지 않고
 * 평평하게 다시 적은 이유는 {@link AcademyListResponse} 와 같다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record StaffAccountListResponse(List<StaffAccountSummaryResponse> items, int page, int size, long totalCount,
        boolean hasNext, Counts counts) {

    /**
     * 상태 탭 건수 — {@code status} <b>만 뺀</b> 같은 조건({@code academy_id}·{@code q})의 건수라 탭을 눌러도 바뀌지 않는다(Ruling 807).
     *
     * @param active   재직 관계자 계정 수
     * @param inactive 퇴사 관계자 계정 수
     */
    public record Counts(long active, long inactive) {
    }

    /** 페이징 봉투에 counts 를 얹는다. */
    public static StaffAccountListResponse of(PageResponse<StaffAccountSummaryResponse> page, Counts counts) {
        return new StaffAccountListResponse(page.items(), page.page(), page.size(), page.totalCount(),
                page.hasNext(), counts);
    }
}
