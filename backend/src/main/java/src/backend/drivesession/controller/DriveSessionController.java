package src.backend.drivesession.controller;

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
import src.backend.drivesession.command.DriveSessionCommandService;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.DriveSessionRosterEntry;
import src.backend.drivesession.dto.StartDriveSessionRequest;
import src.backend.drivesession.query.DriveSessionQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;

/**
 * 운행 세션(기사 운행 시작/종료, Phase 7) API. 관리자·기사 엔드포인트가 갈려
 * 클래스 레벨 대신 메서드마다 {@code @PreAuthorize}를 둔다(rideevent·routing과 동일 스타일).
 */
@RestController
@RequestMapping("/api/drive-sessions")
public class DriveSessionController {

    private final DriveSessionCommandService driveSessionCommandService;
    private final DriveSessionQueryService driveSessionQueryService;

    public DriveSessionController(DriveSessionCommandService driveSessionCommandService,
                                  DriveSessionQueryService driveSessionQueryService) {
        this.driveSessionCommandService = driveSessionCommandService;
        this.driveSessionQueryService = driveSessionQueryService;
    }

    /** 기사: 운행 시작. */
    @PostMapping("/start")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<DriveSessionResponse> start(@AuthenticationPrincipal AuthUser driver,
                                                    @Valid @RequestBody StartDriveSessionRequest request) {
        return ApiResponse.ok(driveSessionCommandService.start(driver, request));
    }

    /** 기사: 운행 종료. */
    @PatchMapping("/{id}/end")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<DriveSessionResponse> end(@AuthenticationPrincipal AuthUser driver, @PathVariable Long id) {
        return ApiResponse.ok(driveSessionCommandService.end(driver, id));
    }

    /** 기사: 이 운행 세션의 당일 명단(이름·위치, 결석 자동 제외). */
    @GetMapping("/{id}/roster")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<List<DriveSessionRosterEntry>> roster(@AuthenticationPrincipal AuthUser driver,
                                                              @PathVariable Long id) {
        return ApiResponse.ok(driveSessionQueryService.getRoster(driver, id));
    }

    /** 기사: 담당 버스 운행 이력. */
    @GetMapping("/bus/{busId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<List<DriveSessionResponse>> busHistory(@AuthenticationPrincipal AuthUser driver,
                                                               @PathVariable Long busId) {
        return ApiResponse.ok(driveSessionQueryService.getBusHistory(driver, busId));
    }

    /** 관리자: 학원 운행 이력(법정 운행기록 열람). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<DriveSessionResponse>> tenantHistory(@AuthenticationPrincipal AuthUser admin,
                                                                  @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(driveSessionQueryService.getTenantHistory(admin, tenantId));
    }
}
