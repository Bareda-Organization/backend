package src.backend.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.admin.command.RunForceConfirmCommandService;
import src.backend.admin.dto.ForceConfirmRequest;
import src.backend.admin.dto.ForceConfirmResponse;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanForceConfirmRun;

/**
 * idle 로 정체된 회차의 강제 확정 콘솔 개입(API_SPEC §6.14, F3 S2 목표 11) — 메인관리자 전용.
 *
 * <p>전 학원 범위이며 학원 격리의 예외다({@link src.backend.account.controller.AdminBlockedAccountController}
 * 와 같은 근거) — 대상은 회차이고, 이 콘솔은 어느 학원 소속인지를 판정에 쓰지 않는다.
 * {@link AuthUser} 를 받는 것은 처리자를 감사 기록에 남기기 위해서다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/runs")
@RequiredArgsConstructor
public class AdminRunForceConfirmController {

    private final RunForceConfirmCommandService runForceConfirmCommandService;

    /** 강제 확정(§6.14) — {@code 201}, {@code route_version} 신규 생성을 반영한다. */
    @CanForceConfirmRun
    @Operation(summary = "강제 확정 콘솔 개입 (Ruling 254, 2026-09-04)")
    @PostMapping("/{runId}/force-confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ForceConfirmResponse> forceConfirm(@PathVariable Long runId,
            @Valid @RequestBody ForceConfirmRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(runForceConfirmCommandService.forceConfirm(runId, authUser.accountId(),
                request.reason()));
    }
}
