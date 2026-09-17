package src.backend.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.admin.dto.AdminEmergencyListResponse;
import src.backend.admin.query.AdminEmergencyQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanMonitorAll;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 관제 API(Phase 11 T2 목표 11) — 이 저장소에서
 * {@code admin} 패키지의 첫 컨트롤러다. 요청자가 메인관리자인지는 {@link CanMonitorAll} 이
 * 판정하므로({@code hasAuthority(MONITOR_ALL)}) 경로 파라미터에 학원을 받지 않는다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/emergencies")
@RequiredArgsConstructor
public class AdminEmergencyController {

    private final AdminEmergencyQueryService adminEmergencyQueryService;

    /**
     * 전 학원 비상 알림 목록, 미확인 경과 시간 포함(목표 11) — {@code status}·{@code academy_id}
     * 쿼리 필터(§6.11, R6 목표 3)는 프런트가 이미 보내고 있던 값이다. {@code @ModelAttribute} 가
     * 아니라 {@code @RequestParam} 인 이유는 {@link src.backend.audit.controller.AuditLogController}
     * 와 같다 — 쿼리 파라미터는 Jackson SNAKE_CASE 전략을 거치지 않는다.
     */
    @CanMonitorAll
    @Operation(summary = "전 학원 비상 알림 (EXC-04, O-07)")
    @GetMapping
    public ApiResponse<AdminEmergencyListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(name = "academy_id", required = false) Long academyId) {
        return ApiResponse.ok(adminEmergencyQueryService.list(status, academyId));
    }
}
