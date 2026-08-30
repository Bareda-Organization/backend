package src.backend.run.navigation.controller;

import java.util.Locale;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
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
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class NavigationController {

    private final NavigationQueryService navigationQueryService;

    @GetMapping("/{runId}/navigation")
    public ApiResponse<NavigationResponse> navigate(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @RequestParam(name = "scope", required = false, defaultValue = "next")
            String scope) {
        NavigationScope parsed = NavigationScope.valueOf(scope.toUpperCase(Locale.ROOT));
        return ApiResponse.ok(navigationQueryService.navigate(requester, runId, parsed));
    }
}
