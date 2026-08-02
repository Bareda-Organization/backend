package src.backend.rideevent.controller;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanCorrectRideEvent;
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.global.security.authz.CanReadAssignedBus;
import src.backend.global.security.authz.CanReadOwnChildren;
import src.backend.global.security.authz.CanReadOwnRecords;
import src.backend.global.security.authz.CanRecordRideEvent;
import src.backend.rideevent.command.RideEventCommandService;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.query.RideEventQueryService;

/**
 * 승하차 기록 API. 같은 기록을 역할별로 다른 범위에서 조회하도록 엔드포인트를 분리하고,
 * 권한 애너테이션으로 역할을 제한한다(세밀한 학원 격리는 서비스 계층에서 추가 검사).
 */
@Tag(name = "09. 승하차(RideEvent)", description = "승하차(BOARD/ALIGHT/HANDOVER) 기록·정정·조회. 기록은 선탑자, 조회는 역할별 범위에서.")
@RestController
@RequestMapping("/api/ride-events")
public class RideEventController {

    private final RideEventCommandService rideEventCommandService;
    private final RideEventQueryService rideEventQueryService;

    public RideEventController(RideEventCommandService rideEventCommandService,
                               RideEventQueryService rideEventQueryService) {
        this.rideEventCommandService = rideEventCommandService;
        this.rideEventQueryService = rideEventQueryService;
    }

    /** 선탑자: 승/하차 기록. 기사는 기록하지 않는다(D-J). */
    @PostMapping
    @CanRecordRideEvent
    @Operation(summary = "승하차 기록 (선탑자 전용)",
            description = "⚠️ **2026-08-02 확장으로 권한이 기사(DRIVER)에서 선탑자(ATTENDANT)로 넘어갔다.** "
                    + "기사 토큰으로 부르면 403 이다 — 기사는 이제 조회만 한다. "
                    + "게다가 **그 버스에 배정된 선탑자 본인**이어야 한다(남의 버스 학생을 기록할 수 없다). "
                    + "`stopId` 를 생략하면 학생의 기본 승차 정류장으로 채워진다. "
                    + "시드 기준 `attendant3@school.com`(최선탑, 3호차) 토큰으로 예시값을 그대로 보내면 성공한다.",
            tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<RideEventResponse> record(@AuthenticationPrincipal AuthUser attendant,
                                                 @Valid @RequestBody RecordRideRequest request) {
        return ApiResponse.ok(rideEventCommandService.record(attendant, request));
    }

    /** 선탑자·관리자: 기록 정정(원본 보존, 정정 기록 신규 생성). */
    @PostMapping("/{id}/correction")
    @CanCorrectRideEvent
    @Operation(summary = "승하차 기록 정정 (선탑자·관리자)",
            description = "⚠️ 권한이 기사에서 **선탑자**로 넘어갔다(관리자는 그대로). "
                    + "원본 행을 고치지 않고 **정정 기록을 새로 만든다** — 잘못 찍힌 기록도 감사 추적을 위해 남긴다. "
                    + "`{id}` 는 정정할 원본 기록의 id 라서, 먼저 `POST /api/ride-events` 로 하나 만들고 그 응답의 id 를 넣는다.")
    public ApiResponse<RideEventResponse> correct(@AuthenticationPrincipal AuthUser actor,
                                                  @Parameter(example = "1", description = "정정 대상 원본 기록 id") @PathVariable Long id,
                                                  @Valid @RequestBody CorrectionRequest request) {
        return ApiResponse.ok(rideEventCommandService.correct(actor, id, request));
    }

    /** 학생: 본인 하루치 기록. */
    @GetMapping("/me")
    @CanReadOwnRecords
    @Operation(summary = "내 승하차 기록 (학생)",
            description = "`date` 를 비우면 오늘이다. 학생 앱은 아직 없어 Swagger 검증용 경로에 가깝다.",
            tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> myRecords(
            @AuthenticationPrincipal AuthUser student,
            @Parameter(example = "2026-07-20", description = "조회일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getMyRecords(student, date));
    }

    /** 학부모: 자녀(형제자매 포함) 하루치 기록. */
    @GetMapping("/children")
    @CanReadOwnChildren
    @Operation(summary = "자녀 승하차 기록 (학부모)",
            description = "연결된 자녀 전원(형제자매 포함)의 하루치 기록. `date` 를 비우면 오늘이다. "
                    + "학부모 앱이 '지금 탔는지/내렸는지'를 판단하는 근거이며, 버스 위치(`/api/locations/children/buses`)와 짝으로 쓴다.",
            tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> childrenRecords(
            @AuthenticationPrincipal AuthUser parent,
            @Parameter(example = "2026-07-20", description = "조회일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getChildrenRecords(parent, date));
    }

    /** 기사·선탑자: 담당 버스 하루치 기록(명단 이력). */
    @GetMapping("/bus/{busId}")
    @CanReadAssignedBus
    @Operation(summary = "담당 버스 승하차 기록 (기사·선탑자)",
            description = "⚠️ **2026-08-02 확장으로 선탑자(ATTENDANT)에게도 열렸다**(기존에는 기사 전용). "
                    + "기록은 못 하지만 조회는 기사도 그대로 할 수 있다. 본인 담당 버스가 아니면 거부된다. "
                    + "`date` 를 비우면 오늘이다.",
            tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> busRecords(
            @AuthenticationPrincipal AuthUser crew,
            @Parameter(example = "1", description = "버스 id(1=3호차)") @PathVariable Long busId,
            @Parameter(example = "2026-07-20", description = "조회일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getRosterRecords(crew, busId, date));
    }

    /** 관리자: 학원 기간 기록(정정 이력 포함). */
    @GetMapping
    @CanMonitorOperations
    @Operation(summary = "학원 승하차 기록 (관리자)",
            description = "기간 조회다. `from`·`to` 를 둘 다 생략하면 **오늘 하루**만 나온다(전체 이력이 아니다). "
                    + "`studentId` 로 한 학생만 좁힐 수 있고, 정정 기록도 함께 나온다.")
    public ApiResponse<List<RideEventResponse>> tenantRecords(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
            @Parameter(example = "1", description = "학생 id(1=김민준). 생략하면 학원 전체") @RequestParam(required = false) Long studentId,
            @Parameter(example = "2026-07-20", description = "시작일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-07-20", description = "종료일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : LocalDate.now();
        return ApiResponse.ok(rideEventQueryService.getTenantRecords(admin, tenantId, studentId, start, end));
    }
}
