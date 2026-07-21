package src.backend.tenant.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.tenant.dto.CreateTenantRequest;
import src.backend.tenant.dto.TenantResponse;
import src.backend.tenant.dto.UpdateTenantLocationRequest;
import src.backend.tenant.command.TenantCommandService;
import src.backend.tenant.query.TenantQueryService;

/**
 * 학원(테넌트) 관리 API. 생성·전체목록은 플랫폼 관리자 전용, 상세는 소속 학원 관리자도 가능.
 */
@Tag(name = "02. 학원(Tenant)", description = "학원(테넌트) 등록·조회·위치 설정. 생성·전체목록은 플랫폼 관리자 전용.")
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantCommandService tenantCommandService;
    private final TenantQueryService tenantQueryService;

    public TenantController(TenantCommandService tenantCommandService, TenantQueryService tenantQueryService) {
        this.tenantCommandService = tenantCommandService;
        this.tenantQueryService = tenantQueryService;
    }

    /** 학원 생성 — 플랫폼 관리자. */
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponse.ok(tenantCommandService.create(request));
    }

    /** 전체 학원 목록 — 플랫폼 관리자. */
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<List<TenantResponse>> list() {
        return ApiResponse.ok(tenantQueryService.list());
    }

    /** 학원 상세 — 플랫폼 관리자 또는 소속 학원 관리자. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<TenantResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                              @Parameter(example = "1") @PathVariable Long id) {
        return ApiResponse.ok(tenantQueryService.get(admin, id));
    }

    /** 학원 위치(depot) 설정 — routing 노선 계산 기준점, 플랫폼 관리자. */
    @PatchMapping("/{id}/location")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<TenantResponse> updateLocation(@Parameter(example = "1") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateTenantLocationRequest request) {
        return ApiResponse.ok(tenantCommandService.updateLocation(id, request));
    }
}
