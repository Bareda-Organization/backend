package src.backend.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanMonitorAll;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.monitoring.query.AdminDashboardQueryService;

/**
 * 메인 관리자 대시보드(API_SPEC §6.18, Ruling 801) — 로그인 뒤 첫 화면(Ruling 800)이 30초마다 한 번 읽는 집계다. 요청자가 메인 관리자인지는
 * {@link CanMonitorAll} 이 판정한다 — 학원은 경로가 아니라 선택 쿼리({@code academy_id})로 받는다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardQueryService adminDashboardQueryService;

    /** 기간 {@code days}(1·7·30, 기본 7)의 운행·지연·로그인 집계 + 지금 처리할 것 + 오늘 회차 + 시스템 상태 + 최근 기록. */
    @CanMonitorAll
    @Operation(summary = "메인 관리자 대시보드 — 첫 화면 집계 (O-02 · O-05)")
    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard(@RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "academy_id", required = false) Long academyId) {
        return ApiResponse.ok(adminDashboardQueryService.dashboard(days, academyId));
    }
}
