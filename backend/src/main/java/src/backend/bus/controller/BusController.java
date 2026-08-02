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
    @Operation(summary = "내 담당 버스(기사·선탑자)",
            description = "토큰 주인이 기사 또는 선탑자로 배정된 버스 1대를 돌려준다. 파라미터가 없어 남의 버스를 볼 수 없다. "
                    + "담당자 앱이 가장 먼저 부르는 API 다 — 여기서 얻은 busId 로 명단·노선·운행세션을 이어서 조회한다. "
                    + "시드 기준 `driver@school.com`(3) 과 `attendant3@school.com`(6) 은 둘 다 3호차(busId=1)가 나온다.",
            tags = {"00. MVP 사용 API", "05. 버스(Bus)"})
    public ApiResponse<BusResponse> myBus(@AuthenticationPrincipal AuthUser crew) {
        return ApiResponse.ok(busQueryService.getMyBus(crew));
    }

    /** 학원 버스 목록(정원 초과 경고 플래그 포함). */
    @Operation(summary = "학원 버스 목록",
            description = "정원 대비 배정 인원과 초과 경고 플래그를 함께 준다. `tenantId` 는 학원 관리자면 생략 가능(본인 학원), "
                    + "플랫폼 관리자는 소속이 없어 필수다.",
            tags = {"00. MVP 사용 API", "05. 버스(Bus)"})
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
    @Operation(summary = "버스 상세(관제용 종합 정보)",
            description = "버스·담당 기사·**선탑자**·해당 일자에 배포된 노선·탑승 명단(학생별 사진·승하차지·보호자)을 한 번에 준다. "
                    + "`date` 를 비우면 오늘 기준이다. "
                    + "⚠️ **운행 세션과 승하차 기록은 여기 들어 있지 않다** — 그 둘은 운행 중 계속 바뀌어 폴링 대상이라 "
                    + "`/api/drive-sessions/bus/{busId}` · `/api/ride-events/bus/{busId}` 로 따로 조회한다. "
                    + "개인정보 밀도가 가장 높은 응답이라 기사·선탑자에게는 열지 않는다(관리자 전용).",
            tags = {"00. MVP 사용 API", "05. 버스(Bus)"})
    @GetMapping("/{id}")
    public ApiResponse<BusDetailResponse> detail(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "버스 id(1=3호차)") @PathVariable Long id,
            @Parameter(example = "2026-08-02", description = "조회 기준일. 생략하면 오늘") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(busQueryService.getBus(admin, id, date));
    }

    /** 버스 생성. */
    @Operation(summary = "버스 등록",
            description = "`tenantId` 는 학원 관리자면 생략 가능(본인 학원). 기사·노선은 나중에 `/assignment` 로 붙여도 된다. "
                    + "선탑자는 생성 시점에 지정할 수 없고 `/assignment` 로만 배정한다.")
    @PostMapping
    public ApiResponse<BusResponse> create(@AuthenticationPrincipal AuthUser admin,
                                           @Valid @RequestBody CreateBusRequest request) {
        return ApiResponse.ok(busCommandService.createBus(admin, request));
    }

    /** 배차 변경 — 담당 기사·선탑자·운행 노선 배정. */
    @Operation(summary = "배차 변경(기사·선탑자·노선)",
            description = "전달한 필드만 바꾼다(생략 = 그대로). `attendantId` 로 **선탑자를 배정**하는 것이 2026-08-02 확장분이다. "
                    + "기사·선탑자↔버스 매칭은 이 API 하나가 전부라 별도 매칭 API 는 없다. "
                    + "⚠️ 같은 학원에서 **한 사람이 두 대에 배정될 수 없다** — 이미 다른 버스에 있으면 409 다"
                    + "(같은 버스에 그대로 다시 배정하는 것은 통과한다). "
                    + "지정한 사용자가 그 학원의 DRIVER/ATTENDANT 가 아니면 400 이다. "
                    + "예시값(버스 1 ← 기사 3 · 선탑자 6 · 노선 1)은 시드의 현재 배정과 같아 그대로 실행해도 안전하다.",
            tags = {"00. MVP 사용 API", "05. 버스(Bus)"})
    @PatchMapping("/{id}/assignment")
    public ApiResponse<BusResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                           @Parameter(example = "1", description = "버스 id(1=3호차)") @PathVariable Long id,
                                           @Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(busCommandService.assign(admin, id, request));
    }
}
