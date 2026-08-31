package src.backend.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.admin.dto.AdminEmergencyListResponse;
import src.backend.admin.query.AdminEmergencyQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanMonitorAll;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 관제 API(Phase 11 T2 목표 11) — 이 저장소에서
 * {@code admin} 패키지의 첫 컨트롤러다. 요청자가 메인관리자인지는 {@link CanMonitorAll} 이
 * 판정하므로({@code hasAuthority(MONITOR_ALL)}) 경로 파라미터에 학원을 받지 않는다.
 */
@RestController
@RequestMapping("/admin/emergencies")
@RequiredArgsConstructor
public class AdminEmergencyController {

    private final AdminEmergencyQueryService adminEmergencyQueryService;

    /** 전 학원 비상 알림 목록, 미확인 경과 시간 포함(목표 11). */
    @CanMonitorAll
    @GetMapping
    public ApiResponse<AdminEmergencyListResponse> list() {
        return ApiResponse.ok(adminEmergencyQueryService.list());
    }
}
