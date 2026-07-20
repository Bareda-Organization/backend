package src.backend.routing.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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
import src.backend.routing.command.RoutingCommandService;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.query.RoutingQueryService;

/**
 * 노선 계획(RoutePlan) API. 생성·승인·배포는 관리자, 배포 완료 계획 조회는 담당 기사 —
 * 역할별로 다른 엔드포인트라 클래스 레벨 대신 메서드마다 {@code @PreAuthorize}를 둔다(rideevent와 동일 스타일).
 */
@RestController
@RequestMapping("/api/route-plans")
public class RoutingController {

    private final RoutingCommandService routingCommandService;
    private final RoutingQueryService routingQueryService;

    public RoutingController(RoutingCommandService routingCommandService, RoutingQueryService routingQueryService) {
        this.routingCommandService = routingCommandService;
        this.routingQueryService = routingQueryService;
    }

    /** 관리자: 자동배치 계획 생성 — sweep+NN+2-opt 로 순서 최적화 후 directions API 1회(또는 청킹) 호출. */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RoutePlanResponse> generate(@AuthenticationPrincipal AuthUser admin,
                                                    @Valid @RequestBody GenerateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.generate(admin, request));
    }

    /** 관리자: 승인(DRAFT/RECOMMENDED → APPROVED). */
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RoutePlanResponse> approve(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.approve(admin, id));
    }

    /** 관리자: 배포(APPROVED → PUBLISHED) — 배포 즉시 담당 기사 조회 API에 노출된다. */
    @PatchMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RoutePlanResponse> publish(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.publish(admin, id));
    }

    /** 관리자: 노선 계획 상세(정차 순서 포함). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RoutePlanResponse> detail(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingQueryService.get(admin, id));
    }

    /** 관리자: 학원(또는 특정 버스) 노선 계획 목록. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<RoutePlanResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                      @RequestParam(required = false) Long tenantId,
                                                      @RequestParam(required = false) Long busId) {
        return ApiResponse.ok(routingQueryService.list(admin, tenantId, busId));
    }

    /** 기사: 담당 버스의 당일(또는 지정일) 배포 완료 노선(등원/하원, pull 방식). */
    @GetMapping("/driver/{busId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<List<RoutePlanResponse>> driverPublished(
            @AuthenticationPrincipal AuthUser driver,
            @PathVariable Long busId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return ApiResponse.ok(routingQueryService.getPublishedForDriver(driver, busId, serviceDate));
    }
}
