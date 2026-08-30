package src.backend.request.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadChangeRequest;
import src.backend.global.security.authz.CanRequestChange;
import src.backend.request.command.ChangeRequestCommandService;
import src.backend.request.dto.ChangeRequestCreateRequest;
import src.backend.request.dto.ChangeRequestCreateResponse;
import src.backend.request.dto.ChangeRequestListResponse;
import src.backend.request.query.ChangeRequestQueryService;

/**
 * 학부모 앱의 일일 변경 신청 API(P-06, API_SPEC §3.8·§3.9).
 *
 * <p>보호자는 토큰이 정한다(§1.5) — {@code {id}} 가 연결된 자녀인지는 {@code student.access} 의 단일
 * 판정 지점({@code LinkedChildLookup})이 본다({@code WeeklyAddressController} 와 같은 형태).
 */
@RestController
@RequestMapping("/students/{id}/change-requests")
@RequiredArgsConstructor
public class ChangeRequestController {

    private final ChangeRequestCommandService changeRequestCommandService;

    private final ChangeRequestQueryService changeRequestQueryService;

    /**
     * 변경 신청(§3.8) — 구간(①즉시·②승인 대기·③마감)에 따라 처리 결과가 갈린다. {@code 201} 인 이유는
     * 구간과 무관하게 {@code change_request} 행이 새로 생기기 때문이다(승인 대기든 즉시 승인이든 자원
     * 생성은 매한가지다).
     */
    @CanRequestChange
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChangeRequestCreateResponse> submit(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId, @Valid @RequestBody ChangeRequestCreateRequest request) {
        return ApiResponse.ok(changeRequestCommandService.submit(authUser, studentId, request));
    }

    /** 신청 상태 조회(§3.9) — 접수 역순 이력 + 대기 중 건수. */
    @CanReadChangeRequest
    @GetMapping
    public ApiResponse<ChangeRequestListResponse> list(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId) {
        return ApiResponse.ok(changeRequestQueryService.list(authUser, studentId));
    }
}
