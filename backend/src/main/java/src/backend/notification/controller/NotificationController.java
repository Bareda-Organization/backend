package src.backend.notification.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.global.security.authz.CanReadOwnChildren;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.query.spec.NotificationQueryService;

/**
 * 알림 조회 API. 발송 자체는 다른 모듈(rideevent 등)이 {@code NotificationCommandService.notify}를
 * 호출해 트리거하고, 여기는 역할별 조회 범위(학부모 알림함 / 관리자 학원 이력)만 노출한다.
 */
@Tag(name = "14. 알림(Notification)", description = "승하차·근접·미승차·노선배포 등 알림 조회. 역할별 범위에서 조회.")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    /** 학부모: 자녀(형제자매 포함) 알림함. */
    @GetMapping("/children")
    @CanReadOwnChildren
    @Operation(summary = "알림함 (학부모)",
            description = "자녀 승하차·근접·미승차·노선 배포·**등하원 위치 변경 판정 결과(LOCATION_CHANGE_RESULT)** 알림이 쌓인다. "
                    + "발송은 다른 모듈이 트리거하고 여기는 조회만 한다.",
            tags = {"00. MVP 사용 API", "14. 알림(Notification)"})
    public ApiResponse<List<NotificationResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(notificationQueryService.getChildrenNotifications(parent));
    }

    /** 관리자: 학원 알림 이력. */
    @GetMapping
    @CanMonitorOperations
    @Operation(summary = "학원 알림 이력 (관리자)",
            description = "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "14. 알림(Notification)"})
    public ApiResponse<List<NotificationResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                          @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(notificationQueryService.getTenantNotifications(admin, tenantId));
    }
}
