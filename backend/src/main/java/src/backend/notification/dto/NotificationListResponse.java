package src.backend.notification.dto;

import java.util.List;

import src.backend.global.response.PageResponse;

/**
 * 알림 목록 응답(API_SPEC §3.12) — §1.8 페이징 봉투에 미읽음 배지를 더한 형태다
 * ({@code SignupRequestListResponse} 와 같은 구조 — 다섯 값을 평평하게 다시 적어 {@code items[]} +
 * {@code unread_count} 를 한 단계로 유지한다).
 *
 * <p>{@code unreadCount} 는 {@code totalCount} 와 <b>다르다</b> — {@code type}·{@code unread_only}
 * 필터를 걸어도 봉투의 미읽음 배지는 전체 기준을 유지해야 화면 배지가 필터에 따라 흔들리지 않는다
 * ({@code pendingCount} 와 같은 근거).
 */
public record NotificationListResponse(List<NotificationItemResponse> items, int page, int size,
        long totalCount, boolean hasNext, long unreadCount) {

    public static NotificationListResponse of(PageResponse<NotificationItemResponse> page, long unreadCount) {
        return new NotificationListResponse(page.items(), page.page(), page.size(), page.totalCount(),
                page.hasNext(), unreadCount);
    }
}
