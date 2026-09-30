package src.backend.audit.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.audit.dto.AuditActorResponse;
import src.backend.audit.query.AuditActorQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.CanReadAudit;

/** 감사 화면의 행위자 찾기 API(API_SPEC §6.13 {@code GET /admin/audit-actors}, R46 감사 · Ruling 447). */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/audit-actors")
@RequiredArgsConstructor
public class AuditActorController {

    private final AuditActorQueryService auditActorQueryService;

    /** 이름 또는 로그인 아이디에 {@code q} 가 들어 있는 계정을 최대 20건 돌려준다. */
    @CanReadAudit
    @Operation(summary = "감사 화면 행위자 찾기 (SYS-01)")
    @GetMapping
    public ApiResponse<AuditActorsResponse> search(@RequestParam(name = "q", required = false) String q) {
        return ApiResponse.ok(new AuditActorsResponse(auditActorQueryService.search(q)));
    }

    /** 응답 봉투 — 다른 목록처럼 {@code items} 아래에 둔다. */
    public record AuditActorsResponse(List<AuditActorResponse> items) {
    }
}
