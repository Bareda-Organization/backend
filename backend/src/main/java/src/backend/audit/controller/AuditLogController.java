package src.backend.audit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.audit.dto.AuditLogItemResponse;
import src.backend.audit.query.AuditLogQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.authz.CanReadAudit;

/**
 * 메인 관리자 콘솔의 개인정보 조회·수정 이력 조회 API(SYS-01, API_SPEC §6.13, Phase 14 T1 목표 3).
 *
 * <p>{@code academy_id}·{@code account_id}·{@code from}·{@code to} 를 {@code @RequestParam} 에
 * 손으로 적는다 — 쿼리 파라미터는 Jackson {@code SNAKE_CASE} 전략을 거치지 않아
 * {@code @ModelAttribute} 로 묶으면 {@code academy_id} 가 조용히 안 붙는다
 * ({@code StaffReportController} 와 같은 근거).
 */
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @CanReadAudit
    @GetMapping
    public ApiResponse<PageResponse<AuditLogItemResponse>> list(
            @RequestParam(name = "academy_id", required = false) Long academyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ApiResponse.ok(auditLogQueryService.list(academyId, accountId, from, to, page, size));
    }
}
