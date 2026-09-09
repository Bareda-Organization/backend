package src.backend.run.navigation.controller;

import java.util.Locale;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadRoute;
import src.backend.run.navigation.dto.NavigationResponse;
import src.backend.run.navigation.service.NavigationQueryService;
import src.backend.run.navigation.service.NavigationScope;

/**
 * 외부 내비 연동(API_SPEC §4.16, RUN-08) — 매니저 앱(기사·동승자)이 부른다. 딥링크는 여기서
 * 만들지 않는다(Ruling 201) — {@code provider} 값만 알려주고 조립은 앱이 한다.
 *
 * <p>{@code scope} 는 쿼리 파라미터라 전역 snake_case 네이밍 전략(Ruling 104)의 적용 대상이 아니다
 * — 이름이 한 단어라 우연히 어긋나지 않을 뿐, 새 파라미터를 더할 때는 이 이름을 직접 준다
 * ({@code StaffRunController} 의 같은 경고 참고).
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class NavigationController {

    private final NavigationQueryService navigationQueryService;

    @CanReadRoute
    @Operation(summary = "외부 내비게이션 앱 연동 (RUN-08, M-09)")
    @GetMapping("/{runId}/navigation")
    public ApiResponse<NavigationResponse> navigate(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @RequestParam(name = "scope", required = false, defaultValue = "next")
            String scope) {
        return ApiResponse.ok(navigationQueryService.navigate(requester, runId, parseScope(scope)));
    }

    /**
     * {@code scope} 는 {@code enum} 이 아니라 {@code String} 으로 받는다 — {@code enum} 으로 받으면
     * Spring 이 바인딩 단계에서 거부해 {@code CanReadRoute} 인가 검사보다 먼저 실패하고, 배치되지 않은
     * 회차에 잘못된 값을 보내도 403 대신 400 이 먼저 난다. 그래서 여기서 직접 파싱해 검증한다
     * ({@code SortParam} 과 같은 이유 — 목록에 없는 값을 그대로 넘기면 500 이 된다).
     */
    private NavigationScope parseScope(String scope) {
        try {
            return NavigationScope.valueOf(scope.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
