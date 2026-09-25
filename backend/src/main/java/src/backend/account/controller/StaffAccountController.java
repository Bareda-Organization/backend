package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.account.command.AccountPasswordResetCommandService;
import src.backend.account.dto.AccountPasswordResetResponse;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanResetAccountPassword;

/**
 * 관계자 웹의 학원 사용자 계정 관리(API_SPEC §5.22) — 지금은 관리자 경유 비밀번호 초기화 하나다(Ruling 329).
 *
 * <p>소속 학원은 토큰이 정한다(§1.5) — 타 학원 계정은 {@code 404} 로 존재를 드러내지 않는다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/accounts")
@RequiredArgsConstructor
public class StaffAccountController {

    private final AccountPasswordResetCommandService accountPasswordResetCommandService;

    /** 비밀번호 초기화(AUTH-08, §5.22) — 응답의 임시 비밀번호는 1회만 보인다. */
    @CanResetAccountPassword
    @Operation(summary = "학부모·학생·매니저 비밀번호 초기화 (AUTH-08)")
    @PostMapping("/{accountId}/password-reset")
    public ApiResponse<AccountPasswordResetResponse> resetPassword(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long accountId) {
        return ApiResponse.ok(accountPasswordResetCommandService.reset(authUser, accountId));
    }
}
