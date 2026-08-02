package src.backend.attendance.controller;

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
    @Operation(summary = "결석·휴원 신고 (학부모)",
            description = "승인되면 그 날짜의 운행 명단(`GET /api/drive-sessions/{id}/roster`)과 노선 시뮬레이션에서 그 학생이 자동으로 빠진다. "
                    + "승인 전에는 아무 영향이 없다.")
    @PostMapping
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<AttendanceExceptionResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                            @Valid @RequestBody CreateAttendanceExceptionRequest request) {
        return ApiResponse.ok(attendanceCommandService.create(parent, request));
    }

    /** 관리자: 승인(PENDING → APPROVED). */
    @Operation(summary = "결석 신고 승인 (PENDING → APPROVED)",
            description = "승인한 순간부터 그 날짜의 명단·배차에서 학생이 제외된다.")
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<AttendanceExceptionResponse> approve(@AuthenticationPrincipal AuthUser admin,
                                                             @Parameter(example = "1", description = "결석 신고 id — 시드에 없으므로 학부모 토큰으로 POST 를 먼저 부른다") @PathVariable Long id) {
        return ApiResponse.ok(attendanceCommandService.approve(admin, id));
    }

    /** 관리자: 반려(PENDING → REJECTED). */
    @Operation(summary = "결석 신고 반려 (PENDING → REJECTED)",
            description = "이미 처리된 신고는 다시 승인·반려할 수 없다.")
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<AttendanceExceptionResponse> reject(@AuthenticationPrincipal AuthUser admin,
                                                            @Parameter(example = "1", description = "결석 신고 id — 시드에 없으므로 학부모 토큰으로 POST 를 먼저 부른다") @PathVariable Long id) {
        return ApiResponse.ok(attendanceCommandService.reject(admin, id));
    }

    /** 학부모: 자녀 신고 이력. */
    @Operation(summary = "내 결석 신고 이력 (학부모)", description = "연결된 자녀 전원의 신고. 파라미터가 없어 남의 자녀 이력을 볼 수 없다.")
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<AttendanceExceptionResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(attendanceQueryService.getChildrenExceptions(parent));
    }

    /** 관리자: 학원 신고 이력. */
    @Operation(summary = "학원 결석 신고 이력 (관리자)", description = "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<AttendanceExceptionResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                  @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(attendanceQueryService.getTenantExceptions(admin, tenantId));
    }
}
