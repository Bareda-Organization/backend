package src.backend.account.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.account.command.SignupCommandService;
import src.backend.account.dto.ReapplyRequestPayload;
import src.backend.account.dto.ReapplyResponse;
import src.backend.account.dto.SignupRequestPayload;
import src.backend.account.dto.SignupResponse;
import src.backend.account.dto.SignupStatusResponse;
import src.backend.account.query.SignupStatusQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.global.security.authz.PublicEndpoint;
import src.backend.global.security.gate.AllowedWhenPending;
import src.backend.global.security.gate.AllowedWhenRejected;

/** 회원가입·가입 심사 상태 조회·거절 후 재신청(AUTH-01·AUTH-03, API_SPEC §2.2·§2.3·§2.4). */
@Tag(name = ApiTags.AUTH)
@RestController
@RequiredArgsConstructor
public class SignupController {

    private final SignupCommandService signupCommandService;
    private final SignupStatusQueryService signupStatusQueryService;

    @PublicEndpoint
    @Operation(summary = "form 회원가입 (AUTH-01, C-01)")
    @PostMapping("/auth/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequestPayload payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(signupCommandService.signup(payload)));
    }

    @AuthenticatedOnly
    @AllowedWhenPending
    @Operation(summary = "승인 대기 화면 (AUTH-03)")
    @GetMapping("/auth/signup-status")
    public ApiResponse<SignupStatusResponse> signupStatus(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(signupStatusQueryService.getStatus(authUser.accountId()));
    }

    @AuthenticatedOnly
    @AllowedWhenRejected
    @Operation(summary = "거절 후 재신청 — 학원 재선택 (AUTH-03)")
    @PostMapping("/auth/signup/reapply")
    public ApiResponse<ReapplyResponse> reapply(@AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody ReapplyRequestPayload payload) {
        return ApiResponse.ok(signupCommandService.reapply(authUser.accountId(), payload));
    }
}
