package src.backend.sos.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.sos.command.SosCommandService;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;
import src.backend.sos.query.SosQueryService;

/**
 * SOS API. 발신은 학생만, 확인·종료는 관리자만 — 조회는 4계층이 각자 범위에서.
 */
@RestController
@RequestMapping("/api/sos-events")
public class SosController {

    private final SosCommandService sosCommandService;
    private final SosQueryService sosQueryService;

    public SosController(SosCommandService sosCommandService, SosQueryService sosQueryService) {
        this.sosCommandService = sosCommandService;
        this.sosQueryService = sosQueryService;
    }

    /** 학생: SOS 발신. */
    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SosEventResponse> trigger(@AuthenticationPrincipal AuthUser student,
                                                 @Valid @RequestBody SosTriggerRequest request) {
        return ApiResponse.ok(sosCommandService.trigger(student, request));
    }

    /** 관리자: 확인(OPEN → ACKNOWLEDGED). */
    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<SosEventResponse> acknowledge(@AuthenticationPrincipal AuthUser admin,
                                                      @PathVariable Long id) {
        return ApiResponse.ok(sosCommandService.acknowledge(admin, id));
    }

    /** 관리자: 종료(ACKNOWLEDGED → RESOLVED). */
    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<SosEventResponse> resolve(@AuthenticationPrincipal AuthUser admin,
                                                 @PathVariable Long id) {
        return ApiResponse.ok(sosCommandService.resolve(admin, id));
    }

    /** 학생: 본인 SOS 이력. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<SosEventResponse>> myEvents(@AuthenticationPrincipal AuthUser student) {
        return ApiResponse.ok(sosQueryService.getMyEvents(student));
    }

    /** 학부모: 자녀 SOS 이력. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<SosEventResponse>> childrenEvents(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(sosQueryService.getChildrenEvents(parent));
    }

    /** 관리자: 학원 SOS 이력. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<SosEventResponse>> tenantEvents(@AuthenticationPrincipal AuthUser admin,
                                                            @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(sosQueryService.getTenantEvents(admin, tenantId));
    }
}
