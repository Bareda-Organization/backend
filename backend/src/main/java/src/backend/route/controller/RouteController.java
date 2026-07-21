package src.backend.route.controller;

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
import src.backend.route.dto.CreateRouteRequest;
import src.backend.route.dto.CreateStopRequest;
import src.backend.route.dto.RouteResponse;
import src.backend.route.dto.StopResponse;
import src.backend.route.command.RouteCommandService;
import src.backend.route.query.RouteQueryService;

/**
 * 노선·정류장 API.
 * 목록·생성·정류장 추가는 관리자 전용, 정류장 조회는 인증된 사용자면 가능.
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteCommandService routeCommandService;
    private final RouteQueryService routeQueryService;

    public RouteController(RouteCommandService routeCommandService, RouteQueryService routeQueryService) {
        this.routeCommandService = routeCommandService;
        this.routeQueryService = routeQueryService;
    }

    /** 노선 목록(정원 초과 경고 포함) — 관리자. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<RouteResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                 @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(routeQueryService.listRoutes(admin, tenantId));
    }

    /** 노선의 정류장 목록(seq 순) — 인증된 사용자. */
    @GetMapping("/{id}/stops")
    public ApiResponse<List<StopResponse>> stops(@Parameter(example = "1") @PathVariable Long id) {
        return ApiResponse.ok(routeQueryService.getStops(id));
    }

    /** 노선 생성 — 관리자. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RouteResponse> create(@AuthenticationPrincipal AuthUser admin,
                                             @Valid @RequestBody CreateRouteRequest request) {
        return ApiResponse.ok(routeCommandService.createRoute(admin, request));
    }

    /** 정류장 추가 — 관리자. */
    @PostMapping("/{id}/stops")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<StopResponse> addStop(@AuthenticationPrincipal AuthUser admin,
                                             @Parameter(example = "1") @PathVariable Long id,
                                             @Valid @RequestBody CreateStopRequest request) {
        return ApiResponse.ok(routeCommandService.addStop(admin, id, request));
    }
}
