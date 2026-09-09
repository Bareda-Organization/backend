package src.backend.monitoring.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorAcademy;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.monitoring.query.StaffDashboardQueryService;

/**
 * 관계자 웹의 운행 대시보드 API(§5.3 {@code GET /staff/dashboard}, MON-01·02·03·04·06, A-03).
 *
 * <p>학원 범위는 {@code StaffRunController} 와 같은 근거로 토큰이 정한다(§1.5) — 경로 파라미터에
 * 학원을 받지 않는다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/dashboard")
@RequiredArgsConstructor
public class StaffDashboardController {

    private final StaffDashboardQueryService staffDashboardQueryService;

    /**
     * 그 날짜의 대시보드 — 날짜를 주지 않으면 오늘이다. {@code date} 를 손으로 적는 이유는
     * {@code StaffRunController#list} 의 {@code service_date} 와 같다(쿼리 파라미터는 Jackson
     * SNAKE_CASE 전략을 타지 않는다).
     */
    @CanMonitorAcademy
    @Operation(summary = "운행 대시보드 · 금일 현황 (MON-01·02·03·04·06, A-03)")
    @GetMapping
    public ApiResponse<StaffDashboardResponse> dashboard(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(name = "date", required = false) String date) {
        return ApiResponse.ok(staffDashboardQueryService.dashboard(requester, date));
    }
}
