package src.backend.attendance.controller;

import java.util.List;

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
import src.backend.attendance.command.AttendanceCommandService;
import src.backend.attendance.dto.AttendanceExceptionResponse;
import src.backend.attendance.dto.CreateAttendanceExceptionRequest;
import src.backend.attendance.query.AttendanceQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;

/**
 * 결석·휴원 신고 API. 신고는 학부모만, 승인·반려는 관리자만 — 조회는 학부모/관리자가 각자 범위에서.
 */
@Tag(name = "11. 결석(Attendance)", description = "결석·휴원 신고·승인·반려. 신고는 학부모, 승인·반려는 관리자.")
@RestController
@RequestMapping("/api/attendance-exceptions")
public class AttendanceController {

    private final AttendanceCommandService attendanceCommandService;
    private final AttendanceQueryService attendanceQueryService;

    public AttendanceController(AttendanceCommandService attendanceCommandService,
                                AttendanceQueryService attendanceQueryService) {
        this.attendanceCommandService = attendanceCommandService;
        this.attendanceQueryService = attendanceQueryService;
    }

    /** 학부모: 자녀 결석·휴원 신고 생성. */
    @PostMapping
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<AttendanceExceptionResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                            @Valid @RequestBody CreateAttendanceExceptionRequest request) {
        return ApiResponse.ok(attendanceCommandService.create(parent, request));
    }

    /** 관리자: 승인(PENDING → APPROVED). */
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<AttendanceExceptionResponse> approve(@AuthenticationPrincipal AuthUser admin,
                                                             @PathVariable Long id) {
        return ApiResponse.ok(attendanceCommandService.approve(admin, id));
    }

    /** 관리자: 반려(PENDING → REJECTED). */
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<AttendanceExceptionResponse> reject(@AuthenticationPrincipal AuthUser admin,
                                                            @PathVariable Long id) {
        return ApiResponse.ok(attendanceCommandService.reject(admin, id));
    }

    /** 학부모: 자녀 신고 이력. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<AttendanceExceptionResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(attendanceQueryService.getChildrenExceptions(parent));
    }

    /** 관리자: 학원 신고 이력. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<AttendanceExceptionResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                  @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(attendanceQueryService.getTenantExceptions(admin, tenantId));
    }
}
