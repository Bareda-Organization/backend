package src.backend.request.controller;

import java.util.Locale;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanApproveChange;
import src.backend.request.command.ChangeRequestDecisionService;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.dto.ApprovalListResponse;
import src.backend.request.dto.DecideChangeRequestRequest;
import src.backend.request.dto.DecideChangeRequestResponse;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.query.ApprovalQueryService;

/**
 * ②구간 승인 대기 목록·상세·결정 API(API_SPEC §5.5·§5.6 {@code /staff/approvals}).
 *
 * <p>결정({@code decide})은 목록·상세와 달리 미리보기 토큰을 검증·소비한다({@link
 * ChangeRequestDecisionService} 참고) — 목록은 재최적화를 실행하지 않고, 상세는 그 시점에 1회
 * 실행해 캐시에 담고, 결정은 그 캐시를 재사용할 뿐 새로 계산하지 않는다.
 */
@RestController
@RequestMapping("/staff/approvals")
@RequiredArgsConstructor
public class StaffApprovalController {

    private final ApprovalQueryService approvalQueryService;

    private final ChangeRequestDecisionService changeRequestDecisionService;

    /**
     * 승인 대기 목록(§5.5 목록) — {@code status} 를 주지 않으면 대기중(pending)만 보여준다.
     *
     * <p>단일 파라미터라 {@code @RequestParam} 에 이름을 손으로 적을 필요는 없지만
     * ({@code StaffRunController} 의 근거는 여러 개로 묶일 때 문제였다), 대문자 enum
     * ({@code PENDING})과 쿼리 값(소문자 {@code pending})의 표기가 다르므로 문자열로 받아 직접
     * 변환한다.
     */
    @CanApproveChange
    @GetMapping
    public ApiResponse<ApprovalListResponse> list(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(required = false) String status) {
        ChangeRequestStatus parsed = status == null
                ? ChangeRequestStatus.PENDING
                : ChangeRequestStatus.valueOf(status.toUpperCase(Locale.ROOT));
        return ApiResponse.ok(approvalQueryService.list(requester, parsed));
    }

    /**
     * 승인 대기 상세(§5.5 상세) — 이 시점에 재최적화를 1회 실행해 전/후 대조를 산출한다.
     *
     * @throws src.backend.global.error.BusinessException {@code 404 APPROVAL_NOT_FOUND}(대상 없음) ·
     *                                                     {@code 403 ACADEMY_SCOPE_VIOLATION}(다른 학원 소속)
     */
    @CanApproveChange
    @GetMapping("/{id}")
    public ApiResponse<ApprovalDetailResponse> detail(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id) {
        return ApiResponse.ok(approvalQueryService.detail(requester, id));
    }

    /**
     * 승인·거절 결정(§5.6) — {@code approve=true} 면 {@code preview_token} 이 필수이고, {@code false}
     * 면 {@code reject_reason} 이 필수다(둘 다 서비스 계층에서 검사한다, {@code
     * ChangeRequestCreateRequest} 와 같은 조건부 필수 패턴).
     *
     * @throws src.backend.global.error.BusinessException {@code 404 APPROVAL_NOT_FOUND} ·
     *                                                     {@code 403 ACADEMY_SCOPE_VIOLATION} ·
     *                                                     {@code 409 APPROVAL_ALREADY_DECIDED} ·
     *                                                     {@code 403 CHANGE_WINDOW_CLOSED} ·
     *                                                     {@code 409 PREVIEW_STALE} ·
     *                                                     {@code 422 VALIDATION_FAILED}
     */
    @CanApproveChange
    @PostMapping("/{id}/decide")
    public ApiResponse<DecideChangeRequestResponse> decide(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody DecideChangeRequestRequest request) {
        return ApiResponse.ok(changeRequestDecisionService.decide(requester, id, request));
    }
}
