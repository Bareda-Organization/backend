package src.backend.academy.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import src.backend.global.response.PageResponse;

/**
 * 학원 목록 응답(API_SPEC §6.1) — §1.8 페이징 봉투에 최상위 {@code summary} 를 더한 형태다.
 *
 * <p>{@link PageResponse} 를 {@link JsonUnwrapped} 로 품는다 — JSON 은 사양대로 "{@code items[]} + {@code summary}" 가 한 단계에
 * 평평하게 나가고, 봉투 다섯 값을 다시 적지 않는다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record AcademyListResponse(@JsonUnwrapped PageResponse<AcademySummaryResponse> page, Summary summary) {

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
        return new AcademyListResponse(page, summary);
    }
}
