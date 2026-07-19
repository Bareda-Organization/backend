package src.backend.schedule.controller;

import java.util.List;

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
    @PostMapping
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<ScheduleChangeRequestResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                              @Valid @RequestBody CreateScheduleChangeRequest request) {
        return ApiResponse.ok(scheduleCommandService.create(parent, request));
    }

    /** 관리자: 승인(PENDING → APPROVED). */
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<ScheduleChangeRequestResponse> approve(@AuthenticationPrincipal AuthUser admin,
                                                               @PathVariable Long id) {
        return ApiResponse.ok(scheduleCommandService.approve(admin, id));
    }

    /** 관리자: 반려(PENDING → REJECTED). */
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<ScheduleChangeRequestResponse> reject(@AuthenticationPrincipal AuthUser admin,
                                                              @PathVariable Long id) {
        return ApiResponse.ok(scheduleCommandService.reject(admin, id));
    }

    /** 학부모: 자녀 요청 이력. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<ScheduleChangeRequestResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(scheduleQueryService.getChildrenRequests(parent));
    }

    /** 관리자: 학원 요청 이력. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<ScheduleChangeRequestResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                    @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(scheduleQueryService.getTenantRequests(admin, tenantId));
    }
}
