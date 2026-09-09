package src.backend.notification.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadNotificationLog;
import src.backend.notification.dto.StaffNotificationListRequest;
import src.backend.notification.dto.StaffNotificationListResponse;
import src.backend.notification.query.StaffNotificationQueryService;

/**
 * 관계자 웹의 알림 로그 전수 조회 API(API_SPEC §5.17, NTF-10·11, A-13).
 *
 * <p>{@code type}·{@code date}·{@code acked}·{@code page}·{@code size} 는 전부 한 단어라
 * {@code @ModelAttribute} 로 묶어도 스네이크케이스 문제가 부재하다({@code StaffStudentController} 와
 * 같은 근거 — {@code run_id} 처럼 여러 단어인 파라미터가 있는 {@code StaffReportController} 만
 * {@code @RequestParam} 을 손으로 쓴다).
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/notifications")
@RequiredArgsConstructor
public class StaffNotificationController {

    private final StaffNotificationQueryService staffNotificationQueryService;

    /** 알림 로그 목록(§5.17) — 학원 관계자만, 소속 학원 범위. */
    @CanReadNotificationLog
    @Operation(summary = "알림 로그 (NTF-10·11, A-13)")
    @GetMapping
    public ApiResponse<StaffNotificationListResponse> list(@AuthenticationPrincipal AuthUser authUser,
            @ModelAttribute StaffNotificationListRequest request) {
        return ApiResponse.ok(staffNotificationQueryService.list(authUser, request));
    }
}
