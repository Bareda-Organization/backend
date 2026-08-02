package src.backend.schedule.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.schedule.command.ScheduleCommandService;
import src.backend.schedule.dto.CreateScheduleChangeRequest;
import src.backend.schedule.dto.ScheduleChangeRequestResponse;
import src.backend.schedule.query.ScheduleQueryService;

/**
 * 등하원 시간 변경 요청 API. 신청은 학부모만, 승인·반려는 관리자만 — 조회는 학부모/관리자가 각자 범위에서.
 */
@Tag(name = "12. 일정변경(Schedule)", description = "등하원 시간 변경 요청·승인·반려. 신청은 학부모, 승인·반려는 관리자.")
@RestController
@RequestMapping("/api/schedule-change-requests")
public class ScheduleController {

    private final ScheduleCommandService scheduleCommandService;
    private final ScheduleQueryService scheduleQueryService;

    public ScheduleController(ScheduleCommandService scheduleCommandService,
                              ScheduleQueryService scheduleQueryService) {
        this.scheduleCommandService = scheduleCommandService;
        this.scheduleQueryService = scheduleQueryService;
    }

    /** 학부모: 자녀 등하원 시간 변경 요청 생성. */
    @Operation(summary = "등하원 시간 변경 요청 (학부모)",
            description = "⚠️ **위치 변경(`15. 위치변경`)과 다른 API 다** — 이쪽은 시각(`requestedTime`)만 다루고, "
                    + "관리자 승인·반려를 거쳐야 효력이 생긴다(자동 판정이 아니다). "
                    + "위치를 바꾸려면 `POST /api/location-change-requests` 를 쓴다.")
    @PostMapping
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<ScheduleChangeRequestResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                              @Valid @RequestBody CreateScheduleChangeRequest request) {
        return ApiResponse.ok(scheduleCommandService.create(parent, request));
    }

    /** 관리자: 승인(PENDING → APPROVED). */
    @Operation(summary = "시간 변경 승인 (PENDING → APPROVED)",
            description = "위치 변경과 달리 **관리자 승인이 필요한** 경로다. `{id}` 는 학부모 토큰으로 POST 를 먼저 불러 얻는다.")
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<ScheduleChangeRequestResponse> approve(@AuthenticationPrincipal AuthUser admin,
                                                               @Parameter(example = "1", description = "시간 변경 요청 id — 시드에 없으므로 학부모 토큰으로 POST 를 먼저 부른다") @PathVariable Long id) {
        return ApiResponse.ok(scheduleCommandService.approve(admin, id));
    }

    /** 관리자: 반려(PENDING → REJECTED). */
    @Operation(summary = "시간 변경 반려 (PENDING → REJECTED)",
            description = "이미 처리된 요청은 다시 승인·반려할 수 없다.")
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<ScheduleChangeRequestResponse> reject(@AuthenticationPrincipal AuthUser admin,
                                                              @Parameter(example = "1", description = "시간 변경 요청 id — 시드에 없으므로 학부모 토큰으로 POST 를 먼저 부른다") @PathVariable Long id) {
        return ApiResponse.ok(scheduleCommandService.reject(admin, id));
    }

    /** 학부모: 자녀 요청 이력. */
    @Operation(summary = "내 시간 변경 요청 이력 (학부모)", description = "연결된 자녀 전원의 요청. 파라미터가 없어 남의 자녀 이력을 볼 수 없다.")
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<ScheduleChangeRequestResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(scheduleQueryService.getChildrenRequests(parent));
    }

    /** 관리자: 학원 요청 이력. */
    @Operation(summary = "학원 시간 변경 요청 이력 (관리자)", description = "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<ScheduleChangeRequestResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                    @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(scheduleQueryService.getTenantRequests(admin, tenantId));
    }
}
