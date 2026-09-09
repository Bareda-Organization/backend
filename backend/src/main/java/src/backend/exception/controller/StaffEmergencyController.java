package src.backend.exception.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.exception.command.EmergencyCommandService;
import src.backend.exception.dto.EmergencyAckResponse;
import src.backend.exception.dto.EmergencyStaffListResponse;
import src.backend.exception.query.EmergencyStaffQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanAckEmergency;

/**
 * 학원 관계자 화면의 비상 알림 조회·확인 API(EXC-04, Phase 11 T2 목표 10) — 메인관리자도
 * {@link CanAckEmergency} 를 통해 같은 경로로 확인할 수 있다({@link EmergencyCommandService#ack}
 * 의 역할별 분기 참고). 목록 조회는 학원 범위만 지원한다 — 메인관리자의 전 학원 목록은
 * {@code GET /admin/emergencies} 다.
 */
@RestController
@RequestMapping("/staff/emergencies")
@RequiredArgsConstructor
public class StaffEmergencyController {

    private final EmergencyStaffQueryService emergencyStaffQueryService;

    private final EmergencyCommandService emergencyCommandService;

    /** 학원 관계자 비상 알림 목록(목표 10). */
    @CanAckEmergency
    @GetMapping
    public ApiResponse<EmergencyStaffListResponse> list(@AuthenticationPrincipal AuthUser requester) {
        return ApiResponse.ok(emergencyStaffQueryService.list(requester));
    }

    /** 비상 신고 확인(ack) 처리(목표 10). */
    @CanAckEmergency
    @PostMapping("/{id}/ack")
    public ApiResponse<EmergencyAckResponse> ack(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id) {
        return ApiResponse.ok(emergencyCommandService.ack(requester, id));
    }
}
