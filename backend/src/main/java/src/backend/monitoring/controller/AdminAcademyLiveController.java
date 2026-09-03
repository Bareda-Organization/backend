package src.backend.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanMonitorAll;
import src.backend.monitoring.dto.AdminAcademyLiveResponse;
import src.backend.monitoring.query.AdminAcademyLiveQueryService;

/**
 * 메인 관리자 콘솔의 학원 1곳 실시간 관제 API(API_SPEC §6.8, O-05, 목표 8·9). 요청자가
 * 메인관리자인지는 {@link CanMonitorAll} 이 판정한다({@code hasAuthority(MONITOR_ALL)}) —
 * 학원 소속 여부와 무관하게 어떤 학원 id 든 조회할 수 있다(§1.5 관리자 콘솔 예외).
 */
@RestController
@RequestMapping("/admin/academies")
@RequiredArgsConstructor
public class AdminAcademyLiveController {

    private final AdminAcademyLiveQueryService adminAcademyLiveQueryService;

    /** 그 학원의 운행 중(moving) 회차 실시간 관제(목표 8·9). */
    @CanMonitorAll
    @GetMapping("/{id}/runs/live")
    public ApiResponse<AdminAcademyLiveResponse> live(@PathVariable Long id) {
        return ApiResponse.ok(adminAcademyLiveQueryService.live(id));
    }
}
