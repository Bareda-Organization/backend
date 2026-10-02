package src.backend.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.admin.command.RunForceFinishCommandService;
import src.backend.admin.dto.ForceFinishRequest;
import src.backend.admin.dto.ForceFinishResponse;
import src.backend.admin.dto.StaleMovingRunListResponse;
import src.backend.admin.query.AdminStaleMovingRunQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanForceFinishRun;
import src.backend.global.security.authz.CanMonitorAll;

/**
 * 운행일이 지난 채 끝나지 않은 이동 중 회차의 목록·강제 종료 콘솔 개입(API_SPEC §6.16·§6.17, R47 Ruling 724) — 메인관리자 전용.
 *
 * <p>전 학원 범위이며 학원 격리의 예외다({@link AdminRunForceConfirmController} 와 같은 근거). {@link AuthUser} 를 받는 것은 처리자를
 * 감사 기록에 남기기 위해서다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/runs")
@RequiredArgsConstructor
public class AdminStaleMovingRunController {

    private final AdminStaleMovingRunQueryService adminStaleMovingRunQueryService;

    private final RunForceFinishCommandService runForceFinishCommandService;

    /** 끝나지 않은 이동 중 회차 목록(§6.16) — {@code StaleMovingRun} 경보가 세는 회차와 같다. */
    @CanMonitorAll
    @Operation(summary = "끝나지 않은 이동 중 회차 목록 (Ruling 724, 2026-10-02)")
    @GetMapping("/stale-moving")
    public ApiResponse<StaleMovingRunListResponse> list() {
        return ApiResponse.ok(adminStaleMovingRunQueryService.list());
    }

    /** 강제 종료(§6.17) — {@code 200}. 탑승자 상태·알림은 건드리지 않는다. */
    @CanForceFinishRun
    @Operation(summary = "끝나지 않은 이동 중 회차 강제 종료 (Ruling 724, 2026-10-02)")
    @PostMapping("/{runId}/force-finish")
    public ApiResponse<ForceFinishResponse> forceFinish(@PathVariable Long runId,
            @Valid @RequestBody ForceFinishRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(runForceFinishCommandService.forceFinish(runId, authUser.accountId(),
                request.reason()));
    }
}
