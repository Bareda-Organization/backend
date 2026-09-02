package src.backend.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.notification.command.NotificationReadCommandService;
import src.backend.notification.dto.NotificationListResponse;
import src.backend.notification.query.NotificationQueryService;

/**
 * 알림 목록·읽음 처리(API_SPEC §3.12·§3.13, NTF-08 · P-09 · S-03) — 전 역할 공통, 권한으로 더 나눌
 * 게 없어 {@link AuthenticatedOnly} 하나만 붙는다({@code StaffReportController} 와 같은 축).
 *
 * <p>쿼리 파라미터를 {@code @ModelAttribute} 가 아니라 낱개 {@code @RequestParam} 으로 받는 이유는
 * Jackson 의 SNAKE_CASE 전략이 쿼리 문자열에는 적용되지 않기 때문이다 — {@code unread_only} 가
 * {@code unreadOnly} 로 자동 바인딩되지 않는다({@code StaffReportController} 의 같은 이유 참고).
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    private final NotificationReadCommandService notificationReadCommandService;

    /** 목록(§3.12) — {@code type}·{@code unread_only} 둘 다 선택, 페이징은 §1.8 공통 규약. */
    @AuthenticatedOnly
    @GetMapping("/notifications")
    public ApiResponse<NotificationListResponse> list(@AuthenticationPrincipal AuthUser authUser,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "unread_only", required = false) Boolean unreadOnly,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ApiResponse.ok(notificationQueryService.list(authUser, type, unreadOnly, page, size));
    }

    /** 읽음 처리(§3.13). */
    @AuthenticatedOnly
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long id) {
        notificationReadCommandService.markRead(authUser, id);
        return ResponseEntity.noContent().build();
    }
}
