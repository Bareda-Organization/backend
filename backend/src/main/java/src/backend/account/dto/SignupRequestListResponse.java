package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import src.backend.global.response.PageResponse;

/**
 * 관계자의 가입 요청 목록 응답(API_SPEC §5.1) — §1.8 페이징 봉투에 미처리 배지를 더한 형태다.
 *
 * <p>{@link PageResponse} 를 {@link JsonUnwrapped} 로 품는다 — JSON 은 §5.1 대로 "{@code items[]} + {@code pending_count}" 가 한 단계에 평평하게
 * 나가고(깊어지면 클라이언트가 다른 목록과 다른 경로로 항목을 읽게 된다), 봉투 다섯 값을 다시 적지 않는다.
 *
 * <p>{@code pendingCount} 는 {@code totalCount} 와 <b>다르다</b> — 필터를 {@code accepted} 로 걸어도
 * 미처리 배지는 대기 건수를 가리켜야 화면의 배지가 필터에 따라 흔들리지 않는다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupRequestListResponse(@JsonUnwrapped PageResponse<SignupRequestSummaryResponse> page,
        long pendingCount) {

    /** 페이징 봉투에 미처리 건수를 붙인다. */
    public static SignupRequestListResponse of(PageResponse<SignupRequestSummaryResponse> page, long pendingCount) {
        return new SignupRequestListResponse(page, pendingCount);
    }
}
