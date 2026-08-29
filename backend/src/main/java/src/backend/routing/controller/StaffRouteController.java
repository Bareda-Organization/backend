package src.backend.routing.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageRoute;
import src.backend.routing.command.RouteCommandService;
import src.backend.routing.command.RouteOptimizeService;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteListRequest;
import src.backend.routing.dto.RouteOptimizeRequest;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteResponse;
import src.backend.routing.dto.RouteUpdateRequest;
import src.backend.routing.query.RouteQueryService;

/**
 * 관계자 웹의 고정 노선 관리 API(RTE-01 · RTE-09 · A-08, API_SPEC §5.9 · Ruling 180).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 경로·본문에 학원을 지정할 자리가 부재하고, 다른 학원의
 * 편성을 {@code {id}} 로 지목하면 {@code 404 ROUTE_NOT_FOUND} 다.
 *
 * <p>당일 확정 노선(출발 30분 전 산출)은 여기 없다 — 저장 위치가 다르고
 * ({@code confirmed_route}·{@code route_version}) 만드는 계기도 사용자 조작이 아니라 시각 도래다
 * (ARCHITECTURE §9). 이 경로가 다루는 것은 학기 단위로 유지되는 <b>원본 편성</b>뿐이다.
 */
@RestController
@RequestMapping("/staff/routes")
@RequiredArgsConstructor
public class StaffRouteController {

    private final RouteQueryService routeQueryService;

    private final RouteCommandService routeCommandService;

    private final RouteOptimizeService routeOptimizeService;

    /** 고정 노선 목록(RTE-01 · A-08, §5.9) — 비활성 편성도 실린다. */
    @CanManageRoute
    @GetMapping
    public ApiResponse<PageResponse<RouteResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @ModelAttribute RouteListRequest request) {
        return ApiResponse.ok(routeQueryService.list(requester, request));
    }

    /** 고정 노선 편성(RTE-01, §5.9) — 같은 차량·요일·방향이 이미 있으면 {@code 409 DUPLICATE_ROUTE}. */
    @CanManageRoute
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RouteDetailResponse> register(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody RouteRegisterRequest request) {
        return ApiResponse.ok(routeCommandService.register(requester, request));
    }

    /** 고정 노선 상세(RTE-01, §5.9) — 정차 순서를 {@code seq} 차례로 함께 싣는다. */
    @CanManageRoute
    @GetMapping("/{id}")
    public ApiResponse<RouteDetailResponse> detail(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id) {
        return ApiResponse.ok(routeQueryService.detail(requester, id));
    }

    /** 고정 노선 수정(RTE-01, §5.9) — §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @CanManageRoute
    @PatchMapping("/{id}")
    public ApiResponse<RouteDetailResponse> update(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody RouteUpdateRequest request) {
        return ApiResponse.ok(routeCommandService.update(requester, id, request));
    }

    /** 고정 노선 삭제(RTE-01, §5.9) — 행을 지우고 정차 순서도 FK CASCADE 로 함께 사라진다. */
    @CanManageRoute
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id) {
        routeCommandService.delete(requester, id);
        return ApiResponse.ok(null);
    }

    /**
     * 정차 순서 최적화(RTE-09, Ruling 180 으로 신설).
     *
     * <p><b>명시적 호출이라는 것이 이 엔드포인트의 존재 이유다.</b> 편성·수정이 자동으로 재배열하지
     * 않는 것은 최적화 가중치가 미확정이기 때문이고(PRD §10.1 G), 기준 없는 재배열이 조용히 돌면
     * 관리자가 정한 차례가 이유 없이 뒤집힌다.
     */
    @CanManageRoute
    @PostMapping("/{id}/optimize")
    public ApiResponse<RouteDetailResponse> optimize(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody RouteOptimizeRequest request) {
        return ApiResponse.ok(routeOptimizeService.optimize(requester, id, request));
    }
}
