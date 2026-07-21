package src.backend.location.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.location.command.BusLocationCommandService;
import src.backend.location.command.LocationCommandService;
import src.backend.location.dto.BusLocationReportRequest;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.dto.LocationView;
import src.backend.location.query.BusLocationQueryService;
import src.backend.location.query.LocationQueryService;

/**
 * 실시간 위치 API. 학생 앱은 자기 좌표를 보고하고(POST), 나머지 계층은 각자 권한 범위에서 조회한다.
 * 역할 제한은 @PreAuthorize 로, 세밀한 학원·담당 격리는 서비스 계층에서 추가 검사한다(승하차 기록과 동일).
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationCommandService locationCommandService;
    private final LocationQueryService locationQueryService;
    private final BusLocationCommandService busLocationCommandService;
    private final BusLocationQueryService busLocationQueryService;

    public LocationController(LocationCommandService locationCommandService,
                              LocationQueryService locationQueryService,
                              BusLocationCommandService busLocationCommandService,
                              BusLocationQueryService busLocationQueryService) {
        this.locationCommandService = locationCommandService;
        this.locationQueryService = locationQueryService;
        this.busLocationCommandService = busLocationCommandService;
        this.busLocationQueryService = busLocationQueryService;
    }

    /** 학생: 자기 현재 위치 보고(실 GPS 전환 대비 엔드포인트, MVP 는 Mock 이 대체). */
    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<Void> report(@AuthenticationPrincipal AuthUser student,
                                    @Valid @RequestBody LocationReportRequest request) {
        locationCommandService.reportSelf(student, request);
        return ApiResponse.ok(null);
    }

    /** 학생: 본인 최신 위치. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<LocationView> myLocation(@AuthenticationPrincipal AuthUser student) {
        return ApiResponse.ok(locationQueryService.getMyLocation(student));
    }

    /** 학부모: 자녀(형제자매 포함) 최신 위치 목록. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<LocationView>> childrenLocations(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(locationQueryService.getChildrenLocations(parent));
    }

    /** 기사: 담당 버스 탑승 학생들의 최신 위치 목록. */
    @GetMapping("/bus/{busId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<List<LocationView>> busLocations(@AuthenticationPrincipal AuthUser driver,
                                                        @Parameter(example = "1") @PathVariable Long busId) {
        return ApiResponse.ok(locationQueryService.getBusLocations(driver, busId));
    }

    /** 관리자: 학원 학생들의 최신 위치 목록(관제). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<LocationView>> tenantLocations(@AuthenticationPrincipal AuthUser admin,
                                                           @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(locationQueryService.getTenantLocations(admin, tenantId));
    }

    /** 기사: 담당 버스의 현재 위치 보고(실 GPS 전환 대비 엔드포인트, MVP는 Mock이 대체, F1). */
    @PostMapping("/bus")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<Void> reportBusLocation(@AuthenticationPrincipal AuthUser driver,
                                               @Valid @RequestBody BusLocationReportRequest request) {
        busLocationCommandService.reportSelf(driver, request);
        return ApiResponse.ok(null);
    }

    /** 관리자: 학원 버스들의 최신 위치 목록(관제 지도 표시용, F1). */
    @GetMapping("/buses")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<BusLocationView>> tenantBusLocations(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(busLocationQueryService.getTenantBusLocations(admin, tenantId));
    }
}
