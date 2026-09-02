package src.backend.notification.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 알림 로그 전수 조회 응답 봉투(API_SPEC §5.17) — 일반 페이징 봉투({@code PageResponse}, §1.8)에
 * {@code unacked_count}(미확인 배지) 를 더한 형태다. {@code PageResponse} 를 재사용하지 않고 별도
 * 레코드로 둔 이유는 그 타입이 배지 같은 부가 집계 필드를 얹을 자리가 없어서다.
 */
public record StaffNotificationListResponse(List<StaffNotificationItemResponse> items, int page, int size,
        long totalCount, boolean hasNext, long unackedCount) {

    public static StaffNotificationListResponse of(Page<?> source, List<StaffNotificationItemResponse> items,
            long unackedCount) {
        return new StaffNotificationListResponse(items, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.hasNext(), unackedCount);
    }
}
