package src.backend.manager.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
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
import src.backend.global.security.authz.CanManageManager;
import src.backend.manager.command.ManagerCommandService;
import src.backend.manager.dto.ManagerListRequest;
import src.backend.manager.dto.ManagerRegisterRequest;
import src.backend.manager.dto.ManagerResponse;
import src.backend.manager.dto.ManagerUpdateRequest;
import src.backend.manager.query.ManagerQueryService;

/**
 * 관계자 웹의 매니저(기사·동승자) 관리 API(MGR-01~04 · A-12, API_SPEC §5.13).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 다른 학원의 매니저를 {@code {id}} 로 지목하면
 * {@code 404 MANAGER_NOT_FOUND} 다.
 *
 * <p>회차 배치(MGR-05·06, §5.14)는 여기 없다 — 대상이 매니저가 아니라 <b>회차</b>이고 경로도
 * {@code /staff/runs/{runId}/assignment} 다.
 */
@RestController
@RequestMapping("/staff/managers")
@RequiredArgsConstructor
public class StaffManagerController {

    private final ManagerQueryService managerQueryService;

    private final ManagerCommandService managerCommandService;

    /** 매니저 목록·검색(MGR-01, §5.13) — 삭제된 매니저는 실리지 않는다. */
    @CanManageManager
    @GetMapping
    public ApiResponse<PageResponse<ManagerResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @ModelAttribute ManagerListRequest request) {
        return ApiResponse.ok(managerQueryService.list(requester, request));
    }

    /** 매니저 등록(MGR-02, §5.13). */
    @CanManageManager
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagerResponse> register(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody ManagerRegisterRequest request) {
        return ApiResponse.ok(managerCommandService.register(requester, request));
    }

    /** 매니저 수정(MGR-03, §5.13) — §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @CanManageManager
    @PatchMapping("/{id}")
    public ApiResponse<ManagerResponse> update(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id,
            @Valid @RequestBody ManagerUpdateRequest request) {
        return ApiResponse.ok(managerCommandService.update(requester, id, request));
    }

    /** 매니저 삭제(MGR-04, §5.13) — 회차에 배치돼 있으면 {@code 409 MANAGER_ASSIGNED}. */
    @CanManageManager
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id) {
        managerCommandService.delete(requester, id);
        return ApiResponse.ok(null);
    }
}
