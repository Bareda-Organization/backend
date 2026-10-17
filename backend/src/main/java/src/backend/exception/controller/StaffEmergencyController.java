package src.backend.exception.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.exception.command.EmergencyCommandService;
import src.backend.exception.dto.EmergencyAckRequest;
import src.backend.exception.dto.EmergencyAckResponse;
import src.backend.exception.dto.EmergencyStaffListResponse;
import src.backend.exception.query.EmergencyStaffQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.Can;
import src.backend.global.security.authz.Permissions;

/**
 * 학원 관계자 화면의 비상 알림 조회·확인 API(EXC-04, Phase 11 T2 목표 10) — 두 경로 모두 학원 관계자 전용이다
 * ({@code @Can(Permissions.EMERGENCY_ACK)}, Ruling 884). 메인관리자는 이 경로에서 목록도 확인도 {@code 403} 이고, 전 학원 열람은
 * {@code GET /admin/emergencies} 를 쓴다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/emergencies")
@RequiredArgsConstructor
public class StaffEmergencyController {

    private final EmergencyStaffQueryService emergencyStaffQueryService;

    private final EmergencyCommandService emergencyCommandService;

    /**
     * 학원 관계자 비상 알림 목록(목표 10, §5.16 필터는 R7 목표 1) — {@code status}·{@code date}
     * 는 쿼리 파라미터라 {@code @ModelAttribute} 대신 손으로 적는다({@link
     * src.backend.exception.controller.StaffReportController} 와 같은 근거).
     */
    @Can(Permissions.EMERGENCY_ACK)
    @Operation(summary = "비상 알림 수신·확인 (EXC-04, A-16)")
    @GetMapping
    public ApiResponse<EmergencyStaffListResponse> list(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "date", required = false) String date) {
        return ApiResponse.ok(emergencyStaffQueryService.list(requester, status, date));
    }

    /** 비상 신고 확인(ack) 처리(목표 10) — 본문은 선택이고, 있으면 조치 메모({@code memo})를 함께 남긴다(Ruling 541). */
    @Can(Permissions.EMERGENCY_ACK)
    @Operation(summary = "비상 알림 확인 응답 (EXC-04, A-16)")
    @PostMapping("/{id}/ack")
    public ApiResponse<EmergencyAckResponse> ack(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody(required = false) EmergencyAckRequest request) {
        return ApiResponse.ok(emergencyCommandService.ack(requester, id, request == null ? null : request.memo()));
    }
}
