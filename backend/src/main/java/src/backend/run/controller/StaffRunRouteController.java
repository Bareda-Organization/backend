package src.backend.run.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorAcademy;
import src.backend.run.dto.StaffRunRouteResponse;
import src.backend.run.query.StaffRunRouteQueryService;

/**
 * 관계자 웹의 확정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route}, RTE-02, F1 S3
 * 목표 11).
 *
 * <p>매니저용 {@code RunRouteController}(§4.3, {@code @CanReadRoute})와 분리한다 — 권한이 다르다
 * (매니저는 자기 배치 회차만, 관계자는 {@link CanMonitorAcademy} 로 학원 전체를 본다). 경로 접두는
 * {@code StaffRosterController}(§5.4 {@code /staff/runs/{runId}/roster})와 같은 계열이라 재사용한다.
 */
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffRunRouteController {

    private final StaffRunRouteQueryService staffRunRouteQueryService;

    @CanMonitorAcademy
    @GetMapping("/{runId}/route")
    public ApiResponse<StaffRunRouteResponse> route(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(staffRunRouteQueryService.route(requester, runId));
    }
}
