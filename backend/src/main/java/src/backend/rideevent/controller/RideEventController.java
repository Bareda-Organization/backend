package src.backend.rideevent.controller;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
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
import src.backend.rideevent.command.RideEventCommandService;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.query.RideEventQueryService;

/**
 * 승하차 기록 API. 같은 기록을 역할별로 다른 범위에서 조회하도록 엔드포인트를 분리하고,
 * @PreAuthorize 로 역할을 제한한다(세밀한 학원 격리는 서비스 계층에서 추가 검사).
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
    @PreAuthorize("hasRole('ATTENDANT')")
    @Operation(tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<RideEventResponse> record(@AuthenticationPrincipal AuthUser attendant,
                                                 @Valid @RequestBody RecordRideRequest request) {
        return ApiResponse.ok(rideEventCommandService.record(attendant, request));
    }

    /** 선탑자·관리자: 기록 정정(원본 보존, 정정 기록 신규 생성). */
    @PostMapping("/{id}/correction")
    @PreAuthorize("hasAnyRole('ATTENDANT', 'ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<RideEventResponse> correct(@AuthenticationPrincipal AuthUser actor,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody CorrectionRequest request) {
        return ApiResponse.ok(rideEventCommandService.correct(actor, id, request));
    }

    /** 학생: 본인 하루치 기록. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> myRecords(
            @AuthenticationPrincipal AuthUser student,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getMyRecords(student, date));
    }

    /** 학부모: 자녀(형제자매 포함) 하루치 기록. */
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    @Operation(tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> childrenRecords(
            @AuthenticationPrincipal AuthUser parent,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getChildrenRecords(parent, date));
    }

    /** 기사·선탑자: 담당 버스 하루치 기록(명단 이력). */
    @GetMapping("/bus/{busId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ATTENDANT')")
    @Operation(tags = {"00. MVP 사용 API", "09. 승하차(RideEvent)"})
    public ApiResponse<List<RideEventResponse>> busRecords(
            @AuthenticationPrincipal AuthUser crew,
            @Parameter(example = "1") @PathVariable Long busId,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rideEventQueryService.getRosterRecords(crew, busId, date));
    }

    /** 관리자: 학원 기간 기록(정정 이력 포함). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<RideEventResponse>> tenantRecords(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1") @RequestParam(required = false) Long tenantId,
            @Parameter(example = "1") @RequestParam(required = false) Long studentId,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-07-20") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : LocalDate.now();
        return ApiResponse.ok(rideEventQueryService.getTenantRecords(admin, tenantId, studentId, start, end));
    }
}
