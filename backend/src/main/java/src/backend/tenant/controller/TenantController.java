package src.backend.tenant.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import src.backend.global.security.authz.CanManageTenants;
import src.backend.global.security.authz.CanReadTenant;
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
    @Operation(summary = "학원 등록 (플랫폼 관리자 전용)",
            description = "`platform@school.com` 토큰이 필요하다 — 학원 관리자 토큰이면 403 이다. "
                    + "`lat`·`lng` 는 노선 계산의 기준점(depot)이라 비워 두면 나중에 노선 생성이 실패한다.")
    @PostMapping
    @CanManageTenants
    public ApiResponse<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponse.ok(tenantCommandService.create(request));
    }

    /** 전체 학원 목록 — 플랫폼 관리자. */
    @Operation(summary = "전체 학원 목록 (플랫폼 관리자 전용)",
            description = "모든 학원을 가로질러 보는 유일한 목록이라 플랫폼 관리자에게만 연다. 시드에는 3개(한빛·가온·미래코딩)가 있다.")
    @GetMapping
    @CanManageTenants
    public ApiResponse<List<TenantResponse>> list() {
        return ApiResponse.ok(tenantQueryService.list());
    }

    /** 학원 상세 — 플랫폼 관리자 또는 소속 학원 관리자. */
    @Operation(summary = "학원 상세",
            description = "학원 관리자는 **본인 학원만** 볼 수 있다 — 남의 학원 id 를 넣으면 거부된다. 플랫폼 관리자는 전부 볼 수 있다.")
    @GetMapping("/{id}")
    @CanReadTenant
    public ApiResponse<TenantResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                              @Parameter(example = "1", description = "학원 id(1=한빛학원)") @PathVariable Long id) {
        return ApiResponse.ok(tenantQueryService.get(admin, id));
    }

    /** 학원 위치(depot) 설정 — routing 노선 계산 기준점, 플랫폼 관리자. */
    @Operation(summary = "학원 위치(depot) 설정 (플랫폼 관리자 전용)",
            description = "모든 노선 계산의 출발·도착 기준점이다. 이 좌표가 없으면 노선 생성·시뮬레이션이 어떤 검사보다 먼저 실패한다. "
                    + "예시값은 시드의 한빛학원 좌표라 그대로 실행해도 값이 바뀌지 않는다.")
    @PatchMapping("/{id}/location")
    @CanManageTenants
    public ApiResponse<TenantResponse> updateLocation(@Parameter(example = "1", description = "학원 id(1=한빛학원)") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateTenantLocationRequest request) {
        return ApiResponse.ok(tenantCommandService.updateLocation(id, request));
    }
}
