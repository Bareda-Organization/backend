package src.backend.account.controller;

import java.util.Locale;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import src.backend.account.command.LoginCommandService;
import src.backend.account.command.LoginResult;
import src.backend.account.command.LogoutCommandService;
import src.backend.account.command.PasswordChangeCommandService;
import src.backend.account.command.RecoverCommandService;
import src.backend.account.command.RefreshCommandService;
import src.backend.account.command.RefreshResult;
import src.backend.account.dto.LoginRequestPayload;
import src.backend.account.dto.LoginResponse;
import src.backend.account.dto.LogoutRequestPayload;
import src.backend.account.dto.PasswordChangeRequestPayload;
import src.backend.account.dto.RecoverRequestPayload;
import src.backend.account.dto.RecoverResponse;
import src.backend.account.dto.RefreshRequestPayload;
import src.backend.account.dto.RefreshResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.global.security.authz.PublicEndpoint;
import src.backend.global.security.gate.AllowedWhenPending;

/**
 * 로그인 · 토큰 재발급 · 로그아웃 · 비밀번호 변경 · 아이디/비밀번호 복구(AUTH-04·05·07·08·09,
 * C-14, API_SPEC §2.5~§2.9).
 *
 * <p><b>클라이언트 종류(app/web) 판정은 이 컨트롤러 한 층에만 둔다</b>(브리프 §3) — 서비스
 * ({@code LoginCommandService} 등)와 저장소는 원문 토큰 문자열만 주고받을 뿐, 그 토큰이 쿠키로
 * 갈지 본문으로 갈지 전혀 모른다. 판정 방식은 두 갈래로 갈라져 있고 서로 겸하지 않는다(§3.2):
 * 로그인은 {@code X-Client-Type} 헤더(§2.5), 그 외 refresh 계열은 쿠키 우선·본문 차선(§2.6·§2.7)이다.
 */
@RestController
public class AuthController {

    private static final String CLIENT_TYPE_WEB = "web";
    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    private final LoginCommandService loginCommandService;
    private final RefreshCommandService refreshCommandService;
    private final LogoutCommandService logoutCommandService;
    private final PasswordChangeCommandService passwordChangeCommandService;
    private final RecoverCommandService recoverCommandService;
    private final RefreshTokenCookieAssembler cookieAssembler;

    public AuthController(LoginCommandService loginCommandService, RefreshCommandService refreshCommandService,
            LogoutCommandService logoutCommandService, PasswordChangeCommandService passwordChangeCommandService,
            RecoverCommandService recoverCommandService, RefreshTokenCookieAssembler cookieAssembler) {
        this.loginCommandService = loginCommandService;
        this.refreshCommandService = refreshCommandService;
        this.logoutCommandService = logoutCommandService;
        this.passwordChangeCommandService = passwordChangeCommandService;
        this.recoverCommandService = recoverCommandService;
        this.cookieAssembler = cookieAssembler;
    }

    /**
     * 로그인(API_SPEC §2.5) — {@code X-Client-Type: web} 이면 refresh 토큰을 본문에서 빼고
     * {@code Set-Cookie} 로만 내린다(§1.2.1). 헤더 미전달 시 {@code app} 취급(기본값).
     */
    @Parameter(name = "X-Client-Type", in = ParameterIn.HEADER,
            schema = @Schema(allowableValues = {"app", "web"}, defaultValue = "app"))
    @PublicEndpoint
    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestHeader(value = "X-Client-Type", required = false, defaultValue = "app") String clientType,
            @Valid @RequestBody LoginRequestPayload payload) {
        LoginResult result = loginCommandService.login(payload.loginId(), payload.password());
        boolean isWeb = CLIENT_TYPE_WEB.equalsIgnoreCase(clientType);

        LoginResponse.Academy academy = result.academyId() == null ? null
                : new LoginResponse.Academy(String.valueOf(result.academyId()), result.academyName());
        LoginResponse body = new LoginResponse(
                result.accessToken(),
                isWeb ? null : result.refreshToken(),
                result.role().name().toLowerCase(Locale.ROOT),
                result.status().name().toLowerCase(Locale.ROOT),
                String.valueOf(result.accountId()),
                academy);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (isWeb) {
            responseBuilder.header(HttpHeaders.SET_COOKIE,
                    cookieAssembler.issue(result.refreshToken(), result.refreshTokenValiditySeconds()).toString());
        }
        return responseBuilder.body(ApiResponse.ok(body));
    }

    /**
     * refresh(API_SPEC §2.6) — 쿠키를 먼저 보고 없으면 본문을 본다. 어디에도 없으면 서비스를
     * 호출하지 않고 즉시 {@code 401 TOKEN_EXPIRED}(§3.2 판정은 이 계층에서만).
     */
    @PublicEndpoint
    @PostMapping("/auth/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequestPayload payload) {
        boolean isWeb = StringUtils.hasText(cookieToken);
        String rawToken = isWeb ? cookieToken : (payload == null ? null : payload.refreshToken());
        if (!StringUtils.hasText(rawToken)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        RefreshResult result = refreshCommandService.refresh(rawToken);
        RefreshResponse body = new RefreshResponse(result.accessToken(), isWeb ? null : result.refreshToken());

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (isWeb) {
            responseBuilder.header(HttpHeaders.SET_COOKIE,
                    cookieAssembler.issue(result.refreshToken(), result.refreshTokenValiditySeconds()).toString());
        }
        return responseBuilder.body(ApiResponse.ok(body));
    }

    /**
     * 로그아웃(API_SPEC §2.7) — {@code pending} 도 호출 가능. 쿠키로 들어온 요청이면 삭제 지시
     * {@code Set-Cookie}(Max-Age=0)를 함께 돌려준다 — 발급 시와 속성이 같아야 브라우저가 같은
     * 쿠키로 인식해 지운다.
     */
    @AuthenticatedOnly
    @AllowedWhenPending
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthUser authUser,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) LogoutRequestPayload payload) {
        boolean isWeb = StringUtils.hasText(cookieToken);
        String rawToken = isWeb ? cookieToken : (payload == null ? null : payload.refreshToken());
        if (!StringUtils.hasText(rawToken)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        logoutCommandService.logout(authUser.accountId(), rawToken);

        ResponseEntity.HeadersBuilder<?> responseBuilder = ResponseEntity.noContent();
        if (isWeb) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookieAssembler.delete().toString());
        }
        return responseBuilder.build();
    }

    /**
     * 비밀번호 변경(API_SPEC §2.8) — 성공 시 기존 refresh 토큰을 전량 무효화한다. 웹 호출 판단은
     * §2.6·§2.7 과 같은 쿠키 존재 여부다(이 엔드포인트도 {@code Path=/api/v1/auth} 하위라 웹
     * 세션이면 브라우저가 refresh 쿠키를 자동으로 함께 보낸다 — Task 4 판단, 보고서 ⑥).
     */
    @AuthenticatedOnly
    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser authUser,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
            @Valid @RequestBody PasswordChangeRequestPayload payload) {
        passwordChangeCommandService.changePassword(authUser.accountId(), payload.currentPassword(),
                payload.newPassword());

        ResponseEntity.HeadersBuilder<?> responseBuilder = ResponseEntity.noContent();
        if (StringUtils.hasText(cookieToken)) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookieAssembler.delete().toString());
        }
        return responseBuilder.build();
    }

    /** 아이디·비밀번호 복구(API_SPEC §2.9) — 응답 스키마는 Task 4 가 설계했다(보고서 ⑥). */
    @PublicEndpoint
    @PostMapping("/auth/recover")
    public ApiResponse<RecoverResponse> recover(@Valid @RequestBody RecoverRequestPayload payload) {
        return ApiResponse.ok(
                recoverCommandService.recover(payload.type(), payload.phone(), payload.verificationCode()));
    }
}
