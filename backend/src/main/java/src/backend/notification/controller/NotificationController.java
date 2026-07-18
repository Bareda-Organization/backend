package src.backend.notification.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.query.spec.NotificationQueryService;

/**
 * 알림 조회 API. 발송 자체는 다른 모듈(rideevent 등)이 {@code NotificationCommandService.notify}를
 * 호출해 트리거하고, 여기는 역할별 조회 범위(학부모 알림함 / 관리자 학원 이력)만 노출한다.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    /** 학부모: 자녀(형제자매 포함) 알림함. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<NotificationResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(notificationQueryService.getChildrenNotifications(parent));
    }

    /** 관리자: 학원 알림 이력. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<NotificationResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                          @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(notificationQueryService.getTenantNotifications(admin, tenantId));
    }
}
