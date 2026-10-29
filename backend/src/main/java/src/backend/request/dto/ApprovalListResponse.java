package src.backend.request.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import src.backend.global.response.PageResponse;

/**
 * 승인 대기 목록 응답(API_SPEC §5.5) — 일반 페이징 봉투({@code PageResponse}, §1.8)의 다섯 키를 그대로 펼치고
 * {@code pending_count}(배지) 를 더한 형태다.
 *
 * <p>{@code pendingCount} 를 별도 필드로 두는 이유는 {@code status} 쿼리로 다른 상태를 조회할 때도
 * "지금 대기 중인 건수" 배지를 화면이 따로 계산하지 않게 하기 위함이다 — {@code items.size()} 는
 * 조회한 페이지의 개수일 뿐 항상 대기 건수와 같지 않다.
 */
public record ApprovalListResponse(long pendingCount,
        @JsonUnwrapped PageResponse<ApprovalSummaryResponse> page) {

    /** 조회 결과의 페이지 정보와 항목, 현재 대기 건수로 응답을 만든다. */
    public static ApprovalListResponse of(Page<?> source, List<ApprovalSummaryResponse> items, long pendingCount) {
        return new ApprovalListResponse(pendingCount, PageResponse.of(source, List.copyOf(items)));
    }
}
