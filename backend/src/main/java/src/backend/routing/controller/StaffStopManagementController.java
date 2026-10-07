package src.backend.routing.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageRoute;
import src.backend.routing.command.StopCommandService;
import src.backend.routing.dto.StopListRequest;
import src.backend.routing.dto.StopManageResponse;
import src.backend.routing.dto.StopUpdateRequest;
import src.backend.routing.query.StopManageQueryService;

/**
 * 관계자 웹의 승하차지 관리 API(RTE-01 · A-08, API_SPEC §5.9 "승하차지 관리" · Ruling 849) — 노선 편성 화면 밖에서 학원의 승하차지를 한곳에
 * 보고 고친다. 새로 만들기·삭제는 두지 않는다(승하차지는 노선 저장과 학생 주소 매칭이 만들고 요일별 주소·지난 회차가 가리킨다).
 *
 * <p>주소 자동완성({@code /staff/stops/suggest})은 {@code student} 모듈의 {@code StaffStopController} 다 — 이 컨트롤러가 {@code routing}
 * 에 있는 것은 목록이 편성({@code route})을 싣고 수정이 노선 잠금 판정을 쓰기 때문이다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/stops")
@RequiredArgsConstructor
public class StaffStopManagementController {

    private final StopManageQueryService stopManageQueryService;

    private final StopCommandService stopCommandService;

    /** 승하차지 목록(§5.9) — 이름 오름차순, {@code q} 는 이름·주소 검색. */
    @CanManageRoute
    @Operation(summary = "승하차지 관리 — 목록 (§1.8 페이징) (RTE-01 · A-08)")
    @GetMapping
    public ApiResponse<PageResponse<StopManageResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @ModelAttribute StopListRequest request) {
        return ApiResponse.ok(stopManageQueryService.list(requester, request));
    }

    /** 승하차지 수정(§5.9) — 보낸 필드만 고치고 변경 후 항목을 그대로 돌려준다(§1.9). */
    @CanManageRoute
    @Operation(summary = "승하차지 관리 — 수정 (RTE-01 · A-08)")
    @PatchMapping("/{id}")
    public ApiResponse<StopManageResponse> update(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id,
            @Valid @RequestBody StopUpdateRequest request) {
        return ApiResponse.ok(stopCommandService.update(requester, id, request));
    }
}
