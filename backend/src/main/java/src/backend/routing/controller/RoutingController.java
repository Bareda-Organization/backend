package src.backend.routing.controller;

import java.util.List;

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
import src.backend.routing.command.RoutingCommandService;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.query.RoutingQueryService;

/**
 * 노선 계획(RoutePlan) API — 생성/조회만(관리자). 승인·배포·기사조회는 Phase 6f에서 추가한다.
 */
@RestController
@RequestMapping("/api/route-plans")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class RoutingController {

    private final RoutingCommandService routingCommandService;
    private final RoutingQueryService routingQueryService;

    public RoutingController(RoutingCommandService routingCommandService, RoutingQueryService routingQueryService) {
        this.routingCommandService = routingCommandService;
        this.routingQueryService = routingQueryService;
    }

    /** 자동배치 계획 생성 — sweep+NN+2-opt 로 순서 최적화 후 directions API 1회(또는 청킹) 호출. */
    @PostMapping("/generate")
    public ApiResponse<RoutePlanResponse> generate(@AuthenticationPrincipal AuthUser admin,
                                                    @Valid @RequestBody GenerateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.generate(admin, request));
    }

    /** 노선 계획 상세(정차 순서 포함). */
    @GetMapping("/{id}")
    public ApiResponse<RoutePlanResponse> detail(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingQueryService.get(admin, id));
    }

    /** 학원(또는 특정 버스) 노선 계획 목록. */
    @GetMapping
    public ApiResponse<List<RoutePlanResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                      @RequestParam(required = false) Long tenantId,
                                                      @RequestParam(required = false) Long busId) {
        return ApiResponse.ok(routingQueryService.list(admin, tenantId, busId));
    }
}
