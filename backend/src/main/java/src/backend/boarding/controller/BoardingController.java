package src.backend.boarding.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.BoardingCommandService;
import src.backend.boarding.dto.RiderRevertRequest;
import src.backend.boarding.dto.RiderRevertResponse;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;

/**
 * 승하차 처리(API_SPEC §4.6)·되돌리기(§4.7) API — 둘 다 동승자 전용이지만, 그 판정은
 * {@code hasAuthority(...)} 애너테이션이 아니라 {@link BoardingCommandService} 안에서 한다
 * (그 클래스 자바독 참고 — {@code 403 ESCORT_ONLY} 를 일반 {@code FORBIDDEN} 과 구별하기 위함).
 * 이 컨트롤러는 {@link AuthenticatedOnly} 로 인증 여부만 확인한다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs/{runId}/riders/{riderId}")
@RequiredArgsConstructor
public class BoardingController {

    private final BoardingCommandService boardingCommandService;

    /** 승하차 처리(§4.6 {@code PATCH}). */
    @AuthenticatedOnly
    @Operation(summary = "승하차 처리 (BRD-01·02, M-12)")
    @PatchMapping
    public ApiResponse<RiderStatusUpdateResponse> updateStatus(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long runId, @PathVariable Long riderId,
            @Valid @RequestBody RiderStatusUpdateRequest request) {
        return ApiResponse.ok(boardingCommandService.updateStatus(authUser, runId, riderId, request));
    }

    /** 상태 정정(§4.7 {@code POST .../revert}) — {@code reason} 이 선택이라 빈 본문도 허용한다. */
    @AuthenticatedOnly
    @Operation(summary = "상태 정정 (BRD-05)")
    @PostMapping("/revert")
    public ApiResponse<RiderRevertResponse> revert(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long runId, @PathVariable Long riderId,
            @RequestBody(required = false) RiderRevertRequest request) {
        RiderRevertRequest body = request != null ? request : new RiderRevertRequest(null);
        return ApiResponse.ok(boardingCommandService.revert(authUser, runId, riderId, body));
    }
}
