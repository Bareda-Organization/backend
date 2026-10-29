package src.backend.notification.controller;


import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.Can;
import src.backend.global.security.authz.Permissions;
import src.backend.notification.command.NotificationSettingCommandService;
import src.backend.notification.dto.NotificationSettingResponse;
import src.backend.notification.dto.NotificationSettingUpdateRequest;
import src.backend.notification.query.NotificationSettingQueryService;

/**
 * 알림 수신 설정 조회·수정 API(API_SPEC §3.14). 승인된 학부모·학생 계정만
 * 쓰는 화면이라 {@code @AllowedWhenPending} 을 붙이지 않았다 — {@code DeviceController} 와 달리
 * 대기 중(pending) 계정이 승인 이전에 이 설정을 조작해야 할 이유가 사양 어디에도 없다(Ruling 145
 * 와 같은 축 — 승인된 계정 전용 기능은 기본값대로 게이트에 걸린다).
 *
 * <p>PATCH 요청 바디는 {@link NotificationSettingUpdateRequest} 로 받아 경계에서 검증한다(대상 밖 키 · boolean 이 아닌 값은 {@code 422}).
 */
@Tag(name = ApiTags.PARENT_STUDENT)
@RestController
@RequestMapping("/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingQueryService notificationSettingQueryService;

    private final NotificationSettingCommandService notificationSettingCommandService;

    /** 알림 설정 조회(§3.14) — 행이 없는 계정은 기본값 DTO 로 응답한다. */
    @Can(Permissions.NOTIFICATION_SETTING_WRITE)
    @Operation(summary = "알림 설정 (NTF-07, P-09)")
    @GetMapping
    public ApiResponse<NotificationSettingResponse> get(@AuthenticationPrincipal AuthUser requester) {
        return ApiResponse.ok(notificationSettingQueryService.get(requester));
    }

    /** §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @Can(Permissions.NOTIFICATION_SETTING_WRITE)
    @Operation(summary = "알림 설정 (NTF-07, P-09)")
    @PatchMapping
    public ApiResponse<NotificationSettingResponse> update(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        return ApiResponse.ok(notificationSettingCommandService.update(requester, request));
    }
}
