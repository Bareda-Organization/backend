package src.backend.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import src.backend.auth.command.AuthCommandService;
import src.backend.auth.dto.LoginRequest;
import src.backend.auth.dto.RefreshRequest;
import src.backend.auth.dto.SignupRequest;
import src.backend.auth.dto.TokenResponse;
import src.backend.auth.query.AuthQueryService;
import src.backend.global.response.ApiResponse;

/**
 * 인증 API — 회원가입/로그인/토큰 재발급. SecurityConfig 에서 공개(permitAll)된 경로다.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "01. 인증(Auth)", description = "다른 API를 테스트하려면 여기서 먼저 로그인해 accessToken을 발급받고, 우측 상단 Authorize에 입력한다.")
public class AuthController {

    private final AuthCommandService authCommandService;
    private final AuthQueryService authQueryService;

    public AuthController(AuthCommandService authCommandService, AuthQueryService authQueryService) {
        this.authCommandService = authCommandService;
        this.authQueryService = authQueryService;
    }

    @PostMapping("/signup")
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
        authCommandService.signup(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    @Operation(tags = {"00. MVP 사용 API", "01. 인증(Auth)"})
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authQueryService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(tags = {"00. MVP 사용 API", "01. 인증(Auth)"})
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authQueryService.refresh(request.refreshToken()));
    }
}
