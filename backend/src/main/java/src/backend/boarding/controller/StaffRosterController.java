package src.backend.boarding.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.StaffRosterItemResponse;
import src.backend.boarding.query.RosterQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStaffRoster;

/**
 * 관계자 웹의 호차별 일일 명단(API_SPEC §5.4 {@code GET /staff/runs/{runId}/roster}, RST-03·A-04,
 * Phase 9 목표 6).
 *
 * <p>{@code StaffRunController}(§5.10 회차 조회·조정)와 같은 URL 계열이지만 별도 클래스다 — 권한
 * ({@code STUDENT_READ_SENSITIVE} vs {@code SCHEDULE_MANAGE})과 바뀌는 계기(명단 표시 규칙 vs 운행
 * 계획)가 갈려 {@code StaffRunAssignmentController} 를 분리한 것과 같은 근거다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffRosterController {

    private final RosterQueryService rosterQueryService;

    @CanReadStaffRoster
    @Operation(summary = "호차별 일일 명단 (RST-03, A-04)")
    @GetMapping("/{runId}/roster")
    public ApiResponse<List<StaffRosterItemResponse>> roster(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(rosterQueryService.staffRoster(requester, runId));
    }
}
