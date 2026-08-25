package src.backend.account.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 관계자의 가입 요청 목록 응답(API_SPEC §5.1) — §1.8 페이징 봉투에 미처리 배지를 더한 형태다.
 *
 * <p>{@link PageResponse} 를 필드로 품지 않고 다섯 값을 평평하게 다시 적은 이유는 §5.1 이 응답을
 * "{@code items[]} + {@code pending_count}" 로 정하기 때문이다 — 품으면 JSON 이 한 단계 깊어져
 * 클라이언트가 다른 목록 엔드포인트와 다른 경로로 항목을 읽게 된다.
 *
 * <p>{@code pendingCount} 는 {@code totalCount} 와 <b>다르다</b> — 필터를 {@code accepted} 로 걸어도
 * 미처리 배지는 대기 건수를 가리켜야 화면의 배지가 필터에 따라 흔들리지 않는다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupRequestListResponse(List<SignupRequestSummaryResponse> items, int page, int size,
        long totalCount, boolean hasNext, long pendingCount) {

    public static SignupRequestListResponse of(PageResponse<SignupRequestSummaryResponse> page, long pendingCount) {
        return new SignupRequestListResponse(page.items(), page.page(), page.size(), page.totalCount(),
                page.hasNext(), pendingCount);
    }
}
