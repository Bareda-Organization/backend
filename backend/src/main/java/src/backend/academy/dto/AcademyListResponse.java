package src.backend.academy.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 학원 목록 응답(API_SPEC §6.1) — §1.8 페이징 봉투에 최상위 {@code summary} 를 더한 형태다.
 *
 * <p>{@link PageResponse} 를 필드로 품지 않고 다섯 값을 평평하게 다시 적은 이유는 사양이 응답을
 * "{@code items[]} + {@code summary}" 로 정하기 때문이다({@code SignupRequestListResponse} 와 같은 근거).
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record AcademyListResponse(List<AcademySummaryResponse> items, int page, int size, long totalCount,
        boolean hasNext, Summary summary) {

    /**
     * 상태 탭 건수와 지표 칸의 재료 — {@code q}·{@code status}·쪽과 <b>무관한 전체 값</b>이다(Ruling 806).
     *
     * @param total     학원 수
     * @param active    운영 중 학원 수
     * @param inactive  비활성 학원 수
     * @param userCount 전 학원 소속 사용자 합 — 학부모·학생·기사·동승자
     */
    public record Summary(long total, long active, long inactive, long userCount) {
    }

    /** 페이징 봉투에 summary 를 얹는다. */
    public static AcademyListResponse of(PageResponse<AcademySummaryResponse> page, Summary summary) {
        return new AcademyListResponse(page.items(), page.page(), page.size(), page.totalCount(), page.hasNext(),
                summary);
    }
}
