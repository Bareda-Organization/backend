package src.backend.notification.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import src.backend.global.response.PageResponse;

/**
 * 알림 목록 응답(API_SPEC §3.12) — §1.8 페이징 봉투({@link PageResponse})에 미읽음 배지를 더한 형태다. 봉투를 {@code @JsonUnwrapped} 로 펼쳐
 * {@code items[]} + {@code unread_count} 를 한 단계로 유지한다.
 *
 * <p>{@code unreadCount} 는 {@code totalCount} 와 <b>다르다</b> — {@code type}·{@code unread_only}
 * 필터를 걸어도 봉투의 미읽음 배지는 전체 기준을 유지해야 화면 배지가 필터에 따라 흔들리지 않는다
 * ({@code pendingCount} 와 같은 근거).
 */
public record NotificationListResponse(@JsonUnwrapped PageResponse<NotificationItemResponse> page, long unreadCount) {

    public static NotificationListResponse of(PageResponse<NotificationItemResponse> page, long unreadCount) {
        return new NotificationListResponse(page, unreadCount);
    }
}
