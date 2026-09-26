package src.backend.request.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 승인 대기 목록 응답(API_SPEC §5.5) — 일반 페이징 봉투({@code PageResponse}, §1.8)에
 * {@code pending_count}(배지) 를 더한 형태다. {@code PageResponse} 를 재사용하지 않고 별도 레코드로
 * 둔 이유는 {@code StaffNotificationListResponse} 와 같다 — 그 타입이 배지 집계 필드를 얹을 자리가
 * 없어서다({@code Ruling 358}).
 *
 * <p>{@code pendingCount} 를 별도 필드로 두는 이유는 {@code status} 쿼리로 다른 상태를 조회할 때도
 * "지금 대기 중인 건수" 배지를 화면이 따로 계산하지 않게 하기 위함이다 — {@code items.size()} 는
 * 조회한 페이지의 개수일 뿐 항상 대기 건수와 같지 않다.
 */
public record ApprovalListResponse(List<ApprovalSummaryResponse> items, long pendingCount, int page, int size,
        long totalCount, boolean hasNext) {

    public static ApprovalListResponse of(Page<?> source, List<ApprovalSummaryResponse> items, long pendingCount) {
        return new ApprovalListResponse(List.copyOf(items), pendingCount, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.hasNext());
    }
}
