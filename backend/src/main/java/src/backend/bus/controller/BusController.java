package src.backend.bus.controller;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
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

    /**
     * 기사·선탑자 본인의 담당 버스 — 담당자 앱이 자기 busId 를 알아내는 진입점.
     * 이 클래스는 기본이 관리자 전용이라 메서드 레벨 {@code @PreAuthorize} 로 DRIVER·ATTENDANT 만 열어 덮어쓴다.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('DRIVER', 'ATTENDANT')")
    @Operation(tags = {"00. MVP 사용 API", "05. 버스(Bus)"})
    public ApiResponse<BusResponse> myBus(@AuthenticationPrincipal AuthUser crew) {
        return ApiResponse.ok(busQueryService.getMyBus(crew));
    }

    /** 학원 버스 목록(정원 초과 경고 플래그 포함). */
    @GetMapping
    public ApiResponse<List<BusResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                               @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(busQueryService.listBuses(admin, tenantId));
    }

    /**
     * 버스 상세 — 버스·기사·선탑자·당일 배포 노선·명단(보호자 포함).
     * 개인정보 밀도가 가장 높은 응답이라 클래스 레벨 관리자 전용 권한을 그대로 둔다(메서드 레벨로 열지 않는다).
     * {@code date} 를 비우면 오늘 기준이다.
     */
    @GetMapping("/{id}")
    public ApiResponse<BusDetailResponse> detail(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1") @PathVariable Long id,
            @Parameter(example = "2026-08-02") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(busQueryService.getBus(admin, id, date));
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
