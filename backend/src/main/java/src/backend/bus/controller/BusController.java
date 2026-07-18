package src.backend.bus.controller;

import java.util.List;

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
import src.backend.bus.service.spec.BusService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;

/**
 * 버스 관리 API(관리자 전용). 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
@RestController
@RequestMapping("/api/buses")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class BusController {

    private final BusService busService;

    public BusController(BusService busService) {
        this.busService = busService;
    }

    /** 학원 버스 목록(정원 초과 경고 플래그 포함). */
    @GetMapping
    public ApiResponse<List<BusResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                               @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(busService.listBuses(admin, tenantId));
    }

    /** 버스 상세 — 노선·기사·탑승/정원·명단. */
    @GetMapping("/{id}")
    public ApiResponse<BusDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                 @PathVariable Long id) {
        return ApiResponse.ok(busService.getBus(admin, id));
    }

    /** 버스 생성. */
    @PostMapping
    public ApiResponse<BusResponse> create(@AuthenticationPrincipal AuthUser admin,
                                           @Valid @RequestBody CreateBusRequest request) {
        return ApiResponse.ok(busService.createBus(admin, request));
    }

    /** 배차 변경 — 담당 기사·운행 노선 배정. */
    @PatchMapping("/{id}/assignment")
    public ApiResponse<BusResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                           @PathVariable Long id,
                                           @Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(busService.assign(admin, id, request));
    }
}
