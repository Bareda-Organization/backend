package src.backend.notification.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 알림 로그 전수 조회 응답 봉투(API_SPEC §5.17) — 일반 페이징 봉투({@code PageResponse}, §1.8)에
 * {@code unacked_count}(미확인 배지) 를 더한 형태다. {@code PageResponse} 를 재사용하지 않고 별도
 * 레코드로 둔 이유는 그 타입이 배지 같은 부가 집계 필드를 얹을 자리가 없어서다. {@code group=true}(Ruling 813)이면 항목이
 * {@link StaffNotificationGroupResponse} 로 바뀐다 — 그래서 항목 타입이 {@code List<?>} 다.
 */
public record StaffNotificationListResponse(List<?> items, int page, int size, long totalCount, boolean hasNext,
        long unackedCount) {

    /** 묶지 않은 목록 — {@code items} 는 {@link StaffNotificationItemResponse}. */
    public static StaffNotificationListResponse of(Page<?> source, List<StaffNotificationItemResponse> items,
            long unackedCount) {
        return new StaffNotificationListResponse(items, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.hasNext(), unackedCount);
    }

    /**
     * 묶어 본 목록(Ruling 813) — {@code items} 는 {@link StaffNotificationGroupResponse}, 쪽 위치와 총계가 <b>묶음</b> 단위다.
     * 쪽 안에서만 묶으면 쪽 경계에서 같은 알림이 갈린다.
     */
    public static StaffNotificationListResponse ofGroups(int page, int size, long totalGroups,
            List<StaffNotificationGroupResponse> items, long unackedCount) {
        return new StaffNotificationListResponse(items, page, size, totalGroups,
                (long) (page + 1) * size < totalGroups, unackedCount);
    }
}
