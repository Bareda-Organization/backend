package src.backend.request.dto;

import java.util.List;

/**
 * 승인 대기 목록 응답(API_SPEC §5.5) — {@code items[]} 와 {@code pending_count} 뿐이다.
 *
 * <p>{@code pendingCount} 를 별도 필드로 두는 이유는 {@code status} 쿼리로 다른 상태를 조회할 때도
 * "지금 대기 중인 건수" 배지를 화면이 따로 계산하지 않게 하기 위함이다 — {@code items.size()} 는
 * 조회한 상태의 개수일 뿐 항상 대기 건수와 같지 않다.
 */
public record ApprovalListResponse(List<ApprovalSummaryResponse> items, long pendingCount) {

    public static ApprovalListResponse of(List<ApprovalSummaryResponse> items, long pendingCount) {
        return new ApprovalListResponse(List.copyOf(items), pendingCount);
    }
}
