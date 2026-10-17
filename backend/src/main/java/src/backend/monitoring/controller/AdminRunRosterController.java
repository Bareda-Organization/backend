package src.backend.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.Can;
import src.backend.global.security.authz.Permissions;
import src.backend.monitoring.dto.AdminRunRosterResponse;
import src.backend.monitoring.query.AdminRunRosterQueryService;

/**
 * 메인 관리자 콘솔의 회차별 승하차지·학생 명단 API(API_SPEC §6.9, O-06). 사진·연락처를
 * 원문으로 담는 L3 조회라 {@code @Can(Permissions.MONITOR_ALL)} 로만 좁히고, 조회 시마다
 * {@link src.backend.monitoring.query.AdminRunRosterQueryService} 가 감사 로그를 남긴다(SYS-01).
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/runs")
@RequiredArgsConstructor
public class AdminRunRosterController {

    private final AdminRunRosterQueryService adminRunRosterQueryService;

    /** 회차 1건의 승하차지별 학생 명단. */
    @Can(Permissions.MONITOR_ALL)
    @Operation(summary = "승하차지별 학생 리스트 (O-06)")
    @GetMapping("/{runId}/roster")
    public ApiResponse<AdminRunRosterResponse> roster(@PathVariable Long runId) {
        return ApiResponse.ok(adminRunRosterQueryService.roster(runId));
    }
}
