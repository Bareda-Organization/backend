package src.backend.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanMonitorAll;
import src.backend.monitoring.dto.AdminRunAttentionResponse;
import src.backend.monitoring.query.AdminRunAttentionQueryService;

/**
 * 메인 관리자 전체 관제의 학원별 오늘 지연·확정 실패 집계(API_SPEC §6.15, Ruling 543). 요청자가 메인 관리자인지는
 * {@link CanMonitorAll} 이 판정한다 — 경로에 학원을 받지 않고 전 학원을 한 번에 센다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/runs")
@RequiredArgsConstructor
public class AdminRunAttentionController {

    private final AdminRunAttentionQueryService adminRunAttentionQueryService;

    /** 오늘 지연 알림이 나간 회차·확정이 실패 중인 회차가 있는 학원과 그 수. */
    @CanMonitorAll
    @Operation(summary = "전체 관제 — 학원별 지연·확정 실패 집계 (O-05)")
    @GetMapping("/attention")
    public ApiResponse<AdminRunAttentionResponse> attention() {
        return ApiResponse.ok(adminRunAttentionQueryService.list());
    }
}
