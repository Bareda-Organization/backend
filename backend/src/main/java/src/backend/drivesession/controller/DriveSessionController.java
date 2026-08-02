package src.backend.drivesession.controller;

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
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.global.security.authz.CanOperateDrive;
import src.backend.global.security.authz.CanReadAssignedBus;

/**
 * 운행 세션(기사 운행 시작/종료, Phase 7) API. 관리자·기사 엔드포인트가 갈려
 * 클래스 레벨 대신 메서드마다 권한 애너테이션을 둔다(rideevent·routing과 동일 스타일).
 */
@Tag(name = "10. 운행세션(DriveSession)",
        description = "운행 시작/종료(기사 전용), 근접·미승차 판정용 세션 상태, 일일 운행 로그. "
                + "명단·이력 조회는 선탑자(ATTENDANT)에게도 열려 있다.")
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
    @CanOperateDrive
    @Operation(summary = "운행 시작 (기사 전용)",
            description = "⚠️ 승하차 기록은 선탑자로 넘어갔지만 **운행 세션 시작·종료는 기사가 그대로 유지**한다 — "
                    + "선탑자 토큰으로 부르면 403 이다. 본인 담당 버스만 시작할 수 있고, `serviceDate` 를 비우면 오늘이다. "
                    + "같은 버스·방향·날짜로 이미 세션이 있으면 거부된다. "
                    + "여기서 만든 세션 id 가 명단(`/{id}/roster`)과 학부모 위치 변경 차단(I-4)의 기준이 된다.",
            tags = {"00. MVP 사용 API", "10. 운행세션(DriveSession)"})
    public ApiResponse<DriveSessionResponse> start(@AuthenticationPrincipal AuthUser driver,
                                                    @Valid @RequestBody StartDriveSessionRequest request) {
        return ApiResponse.ok(driveSessionCommandService.start(driver, request));
    }

    /** 기사: 운행 종료. */
    @PatchMapping("/{id}/end")
    @CanOperateDrive
    @Operation(summary = "운행 종료 (기사 전용)",
            description = "시작과 마찬가지로 **기사만** 가능하다. `{id}` 는 `POST /api/drive-sessions/start` 응답 또는 "
                    + "`GET /api/drive-sessions/bus/{busId}` 에서 얻은 세션 id 다(시드에는 세션이 없으므로 먼저 하나 시작해야 한다).",
            tags = {"00. MVP 사용 API", "10. 운행세션(DriveSession)"})
    public ApiResponse<DriveSessionResponse> end(@AuthenticationPrincipal AuthUser driver,
                                                  @Parameter(example = "1", description = "운행 세션 id(start 응답의 id)") @PathVariable Long id) {
        return ApiResponse.ok(driveSessionCommandService.end(driver, id));
    }

    /** 기사·선탑자: 이 운행 세션의 당일 명단(이름·사진·위치, 결석 자동 제외). */
    @GetMapping("/{id}/roster")
    @CanReadAssignedBus
    @Operation(summary = "운행 명단 (기사·선탑자)",
            description = "⚠️ **2026-08-02 확장으로 선탑자(ATTENDANT)에게 열렸다.** 선탑자 앱의 승하차 체크 화면이 이걸 그린다. "
                    + "학생별 **이름·사진(photoUrl)·승하차지 좌표**를 주며, 승인된 결석 신고가 있는 학생은 자동으로 빠진다. "
                    + "`{id}` 는 운행 세션 id 다 — 시드에는 세션이 없으므로 기사 토큰으로 `POST /start` 를 먼저 부르거나 "
                    + "`GET /api/drive-sessions/bus/{busId}` 로 id 를 찾아 넣는다.",
            tags = {"00. MVP 사용 API", "10. 운행세션(DriveSession)"})
    public ApiResponse<List<DriveSessionRosterEntry>> roster(@AuthenticationPrincipal AuthUser crew,
                                                              @Parameter(example = "1", description = "운행 세션 id") @PathVariable Long id) {
        return ApiResponse.ok(driveSessionQueryService.getRoster(crew, id));
    }

    /** 기사·선탑자: 담당 버스 운행 이력. 선탑자 앱은 여기서 진행 중인 세션 id 를 고른다(§4.1 사슬). */
    @GetMapping("/bus/{busId}")
    @CanReadAssignedBus
    @Operation(summary = "담당 버스 운행 이력 (기사·선탑자)",
            description = "⚠️ **2026-08-02 확장으로 선탑자에게 열렸다** — 이 한 칸이 막히면 선탑자 앱이 세션 id 를 알 수 없어 "
                    + "명단 화면이 통째로 뜨지 않는다. 담당자 앱의 호출 순서는 "
                    + "`GET /api/buses/me` → `GET /api/drive-sessions/bus/{busId}` → `GET /api/drive-sessions/{id}/roster` 다. "
                    + "'진행 중 세션'만 주는 전용 API 가 없어 전체 이력을 받아 앱이 고른다(endedAt 이 null 인 것).",
            tags = {"00. MVP 사용 API", "10. 운행세션(DriveSession)"})
    public ApiResponse<List<DriveSessionResponse>> busHistory(@AuthenticationPrincipal AuthUser crew,
                                                               @Parameter(example = "1", description = "버스 id(1=3호차)") @PathVariable Long busId) {
        return ApiResponse.ok(driveSessionQueryService.getBusHistory(crew, busId));
    }

    /** 관리자: 학원 운행 이력(법정 운행기록 열람). */
    @GetMapping
    @CanMonitorOperations
    @Operation(summary = "학원 운행 이력 (관리자)",
            description = "법정 운행기록 열람용. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다. "
                    + "관제 화면은 버스 상세(`GET /api/buses/{id}`)와 달리 갱신이 잦은 이 정보를 따로 폴링한다.")
    public ApiResponse<List<DriveSessionResponse>> tenantHistory(@AuthenticationPrincipal AuthUser admin,
                                                                  @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(driveSessionQueryService.getTenantHistory(admin, tenantId));
    }
}
