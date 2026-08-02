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

    @Operation(summary = "회원가입(self-service)",
            description = "인증 없이 부를 수 있는 공개 경로다. 그래서 **관리자가 부여하는 역할은 스스로 가질 수 없다** — "
                    + "`ATTENDANT`·`ACADEMY_ADMIN`·`PLATFORM_ADMIN` 을 넣으면 403 이다(권한 상승 차단). "
                    + "선탑자 계정은 반드시 관리자가 `POST /api/members` 로 만든다. "
                    + "`tenantId` 는 필수다. 같은 이메일로 두 번 부르면 중복 오류이므로 예시 이메일을 바꿔 가며 시험한다.")
    @PostMapping("/signup")
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
        authCommandService.signup(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    @Operation(summary = "로그인 — 여기서 먼저 토큰을 받는다",
            description = "응답의 `accessToken` 을 우측 상단 **Authorize** 에 넣으면 이후 모든 요청에 자동으로 붙는다. "
                    + "데모 계정은 문서 최상단 표를 보라 — 비밀번호는 전부 `password` 다. "
                    + "역할별로 열린 API 가 다르므로, 403 이 나면 **토큰의 역할부터 확인**한다"
                    + "(예: 승하차 기록은 `attendant3@school.com`, 운행 시작은 `driver@school.com`).",
            tags = {"00. MVP 사용 API", "01. 인증(Auth)"})
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authQueryService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "액세스 토큰 재발급",
            description = "로그인 응답의 `refreshToken` 을 그대로 넣는다(accessToken 이 아니다). "
                    + "만료·위조된 리프레시 토큰이면 401 이다.",
            tags = {"00. MVP 사용 API", "01. 인증(Auth)"})
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authQueryService.refresh(request.refreshToken()));
    }
}
