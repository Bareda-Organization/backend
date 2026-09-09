package src.backend.run.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadRoute;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.query.RunRouteQueryService;

/**
 * 매니저 앱의 실시간 노선(API_SPEC §4.3 {@code GET /runs/{runId}/route}, RUN-03·M-08·M-09,
 * Ruling 205, Phase 9 목표 8).
 *
 * <p>{@link CanReadRoute} 는 전 역할에 열려 있어 이 애너테이션만으로는 회차를 좁히지 못한다 — 실제
 * 좁히는 판정은 {@code RunRouteQueryService} 안의 {@code ManagerRunAccess} 다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class RunRouteController {

    private final RunRouteQueryService runRouteQueryService;

    @CanReadRoute
    @Operation(summary = "운행 정보 (M-09 · LOC-03)")
    @GetMapping("/{runId}/route")
    public ApiResponse<RunRouteResponse> route(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(runRouteQueryService.route(requester, runId));
    }
}
