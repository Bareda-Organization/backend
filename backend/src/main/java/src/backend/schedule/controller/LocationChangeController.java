package src.backend.schedule.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.schedule.command.LocationChangeCommandService;
import src.backend.schedule.dto.CreateLocationChangeRequest;
import src.backend.schedule.dto.LocationChangeRequestResponse;
import src.backend.schedule.query.LocationChangeQueryService;

/**
 * 등하원 위치 변경 요청 API(P2). 신청은 학부모만 — 승인 단계가 없고 접수 즉시 자동 판정된다(D-H).
 * 관리자에게는 승인 권한이 아니라 <b>감사용 이력 조회</b>만 열려 있다.
 */
@Tag(name = "12. 일정변경(Schedule)", description = "등하원 시간 변경 요청·승인·반려. 신청은 학부모, 승인·반려는 관리자.")
@RestController
@RequestMapping("/api/location-change-requests")
public class LocationChangeController {

    private final LocationChangeCommandService locationChangeCommandService;
    private final LocationChangeQueryService locationChangeQueryService;

    public LocationChangeController(LocationChangeCommandService locationChangeCommandService,
                                    LocationChangeQueryService locationChangeQueryService) {
        this.locationChangeCommandService = locationChangeCommandService;
        this.locationChangeQueryService = locationChangeQueryService;
    }

    /** 학부모: 자녀 등하원 위치 변경 신청(자동 판정). */
    @PostMapping
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<LocationChangeRequestResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                            @Valid @RequestBody CreateLocationChangeRequest request) {
        return ApiResponse.ok(locationChangeCommandService.create(parent, request));
    }

    /** 학부모: 내 신청 이력. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<LocationChangeRequestResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(locationChangeQueryService.getChildrenRequests(parent));
    }

    /** 관리자: 학원 신청 이력(반려·차단 포함 — 감사 목적). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<LocationChangeRequestResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                   @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(locationChangeQueryService.getTenantRequests(admin, tenantId));
    }
}
