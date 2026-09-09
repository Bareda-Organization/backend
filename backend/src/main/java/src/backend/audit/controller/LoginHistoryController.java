package src.backend.audit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.audit.dto.LoginHistoryItemResponse;
import src.backend.audit.query.LoginHistoryQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.authz.CanReadAudit;

/**
 * 메인 관리자 콘솔의 로그인·차단 이력 조회 API(SYS-02, API_SPEC §6.13, Phase 14 T1 목표 4).
 *
 * <p>필터 선언 방식은 {@link AuditLogController} 와 같은 근거를 따른다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/login-history")
@RequiredArgsConstructor
public class LoginHistoryController {

    private final LoginHistoryQueryService loginHistoryQueryService;

    @CanReadAudit
    @Operation(summary = "접속 이력 조회 (SYS-02)")
    @GetMapping
    public ApiResponse<PageResponse<LoginHistoryItemResponse>> list(
            @RequestParam(name = "academy_id", required = false) Long academyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ApiResponse.ok(loginHistoryQueryService.list(academyId, accountId, from, to, page, size));
    }
}
