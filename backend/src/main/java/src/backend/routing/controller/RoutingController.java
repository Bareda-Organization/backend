package src.backend.routing.controller;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import src.backend.routing.dto.AutoAssignRequest;
import src.backend.routing.dto.AutoAssignResponse;
import src.backend.routing.dto.ConfirmAutoAssignRequest;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.query.RoutingQueryService;

/**
 * 노선 계획(RoutePlan) API. 생성·승인·배포는 관리자, 배포 완료 계획 조회는 담당 기사 —
 * 역할별로 다른 엔드포인트라 클래스 레벨 대신 메서드마다 {@code @PreAuthorize}를 둔다(rideevent와 동일 스타일).
 */
@Tag(name = "07. 배차·노선계획(Routing)", description = "노선 계획 생성, 멀티버스 자동배정(F4)/확정, 승인·배포, 기사 조회.")
@RestController
@RequestMapping("/api/route-plans")
public class RoutingController {

    private final RoutingCommandService routingCommandService;
    private final RoutingQueryService routingQueryService;
    private final RoutePlanSimulationService routePlanSimulationService;

    public RoutingController(RoutingCommandService routingCommandService,
                             RoutingQueryService routingQueryService,
                             RoutePlanSimulationService routePlanSimulationService) {
        this.routingCommandService = routingCommandService;
        this.routingQueryService = routingQueryService;
        this.routePlanSimulationService = routePlanSimulationService;
    }

    /** 관리자: 자동배치 계획 생성 — sweep+NN+2-opt 로 순서 최적화 후 directions API 1회(또는 청킹) 호출. */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RoutePlanResponse> generate(@AuthenticationPrincipal AuthUser admin,
                                                    @Valid @RequestBody GenerateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.generate(admin, request));
    }

    /** 관리자: 멀티버스 자동 배정(제안) — Sweep 클러스터링으로 버스별 RECOMMENDED 계획을 만든다(배정 확정 전, F4). */
    @PostMapping("/auto-assign")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<AutoAssignResponse> autoAssign(@AuthenticationPrincipal AuthUser admin,
                                                       @Valid @RequestBody AutoAssignRequest request) {
        return ApiResponse.ok(routingCommandService.autoAssign(admin, request));
    }

    /** 관리자: 자동 배정 확정 — 학생 배정을 커밋하고 승인·배포까지 이어서 수행한다(F4). */
    @PostMapping("/auto-assign/confirm")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> confirmAutoAssign(@AuthenticationPrincipal AuthUser admin,
                                                                   @Valid @RequestBody ConfirmAutoAssignRequest request) {
        return ApiResponse.ok(routingCommandService.confirmAutoAssign(admin, request.planIds()));
    }

    /** 관리자: 배차 변경안 시뮬레이션 — 저장하지 않고 baseline vs candidate 를 비교한다(요구 8). */
    @PostMapping("/simulate")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "배차 변경안 비교(미저장)",
            description = "overrides 를 적용해 노선을 재계산하고 거리·시간·정차 수 델타를 돌려준다. 어떤 행도 저장하지 않는다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanComparison> simulate(@AuthenticationPrincipal AuthUser admin,
                                                      @Valid @RequestBody SimulateRoutePlanRequest request) {
        return ApiResponse.ok(routePlanSimulationService.simulate(admin, request));
    }

    /** 관리자: 배차 변경안 채택 — 요청의 overrides 로 서버가 재계산해 배정 커밋 + version+1 계획 배포까지 수행한다. */
    @PostMapping("/simulate/apply")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "배차 변경안 채택",
            description = "요청의 overrides 로 서버가 다시 계산해 저장한다(비교 응답을 그대로 보내지 않는다). 정원을 초과하면 거부한다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> applySimulation(@AuthenticationPrincipal AuthUser admin,
                                                           @Valid @RequestBody SimulateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.applySimulation(admin, request));
    }

    /** 관리자: 승인(DRAFT/RECOMMENDED → APPROVED). */
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> approve(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.approve(admin, id));
    }

    /** 관리자: 배포(APPROVED → PUBLISHED) — 배포 즉시 담당 기사 조회 API에 노출된다. */
    @PatchMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> publish(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.publish(admin, id));
    }

    /** 관리자: 노선 계획 상세(정차 순서 포함). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> detail(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return ApiResponse.ok(routingQueryService.get(admin, id));
    }

    /** 관리자: 학원(또는 특정 버스) 노선 계획 목록. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                      @Parameter(example = "1") @RequestParam(required = false) Long tenantId,
                                                      @Parameter(example = "1") @RequestParam(required = false) Long busId) {
        return ApiResponse.ok(routingQueryService.list(admin, tenantId, busId));
    }

    /** 기사·선탑자: 담당 버스의 당일(또는 지정일) 배포 완료 노선(등원/하원, pull 방식). */
    @GetMapping("/driver/{busId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ATTENDANT')")
    @Operation(tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> driverPublished(
            @AuthenticationPrincipal AuthUser crew,
            @Parameter(example = "1") @PathVariable Long busId,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return ApiResponse.ok(routingQueryService.getPublishedForDriver(crew, busId, serviceDate));
    }
}
