package src.backend.route.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import src.backend.global.security.authz.CanManageRoutes;
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
@Tag(name = "06. 노선(Route)", description = "노선·정류장 등록·조회. 목록·생성·정류장 추가는 관리자 전용.")
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
    @Operation(summary = "노선 목록",
            description = "배정 정원 대비 인원과 초과 경고를 함께 준다. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다. "
                    + "시드 기준 한빛학원은 1(하원 A노선)·2(하원 B노선, 정원 2)다.")
    @GetMapping
    @CanManageRoutes
    public ApiResponse<List<RouteResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                 @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(routeQueryService.listRoutes(admin, tenantId));
    }

    /** 노선의 정류장 목록(seq 순) — 인증된 사용자. */
    @Operation(summary = "노선의 정류장 목록",
            description = "seq 순으로 준다. ⚠️ 이 노선(`Route`)·정류장(`Stop`)은 **행정용 마스터 데이터**이고, "
                    + "실제 운행 경로는 매일 계산되는 노선 계획(`/api/route-plans`)이다 — 둘을 혼동하지 않는다. "
                    + "학원 관리자 외 역할도 인증만 되면 조회할 수 있다(정류장은 개인정보가 아니다).")
    @GetMapping("/{id}/stops")
    public ApiResponse<List<StopResponse>> stops(@Parameter(example = "1", description = "노선 id(1=하원 A노선)") @PathVariable Long id) {
        return ApiResponse.ok(routeQueryService.getStops(id));
    }

    /** 노선 생성 — 관리자. */
    @Operation(summary = "노선 등록",
            description = "`assignCapacity` 는 이 노선에 배정할 수 있는 학생 수 상한이며, 버스 좌석 수(`Bus.seatCapacity`)와는 별개다.")
    @PostMapping
    @CanManageRoutes
    public ApiResponse<RouteResponse> create(@AuthenticationPrincipal AuthUser admin,
                                             @Valid @RequestBody CreateRouteRequest request) {
        return ApiResponse.ok(routeCommandService.createRoute(admin, request));
    }

    /** 정류장 추가 — 관리자. */
    @Operation(summary = "정류장 추가",
            description = "⚠️ 정류장은 **여러 학생이 공유하는 행**이다 — 특정 학생 한 명의 위치를 바꾸려고 이 좌표를 고치면 "
                    + "같은 정류장을 쓰는 다른 학생까지 통째로 끌려간다. 개인별 위치는 학생 쪽 `/dropoff`·위치 변경 신청으로 바꾼다.")
    @PostMapping("/{id}/stops")
    @CanManageRoutes
    public ApiResponse<StopResponse> addStop(@AuthenticationPrincipal AuthUser admin,
                                             @Parameter(example = "1", description = "노선 id(1=하원 A노선)") @PathVariable Long id,
                                             @Valid @RequestBody CreateStopRequest request) {
        return ApiResponse.ok(routeCommandService.addStop(admin, id, request));
    }
}
