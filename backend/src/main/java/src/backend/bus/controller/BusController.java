package src.backend.bus.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.bus.dto.AssignmentRequest;
import src.backend.bus.dto.BusDetailResponse;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.CreateBusRequest;
import src.backend.bus.command.BusCommandService;
import src.backend.bus.query.BusQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;

/**
 * 버스 관리 API(관리자 전용). 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
@Tag(name = "05. 버스(Bus)", description = "버스 등록·조회·기사/노선 배정. 관리자 전용.")
@RestController
@RequestMapping("/api/buses")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class BusController {

    private final BusCommandService busCommandService;
    private final BusQueryService busQueryService;

    public BusController(BusCommandService busCommandService, BusQueryService busQueryService) {
        this.busCommandService = busCommandService;
        this.busQueryService = busQueryService;
    }

    /** 학원 버스 목록(정원 초과 경고 플래그 포함). */
    @GetMapping
    public ApiResponse<List<BusResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                               @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(busQueryService.listBuses(admin, tenantId));
    }

    /** 버스 상세 — 노선·기사·탑승/정원·명단. */
    @GetMapping("/{id}")
    public ApiResponse<BusDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                 @Parameter(example = "1") @PathVariable Long id) {
        return ApiResponse.ok(busQueryService.getBus(admin, id));
    }

    /** 버스 생성. */
    @PostMapping
    public ApiResponse<BusResponse> create(@AuthenticationPrincipal AuthUser admin,
                                           @Valid @RequestBody CreateBusRequest request) {
        return ApiResponse.ok(busCommandService.createBus(admin, request));
    }

    /** 배차 변경 — 담당 기사·운행 노선 배정. */
    @PatchMapping("/{id}/assignment")
    public ApiResponse<BusResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                           @Parameter(example = "1") @PathVariable Long id,
                                           @Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(busCommandService.assign(admin, id, request));
    }
}
