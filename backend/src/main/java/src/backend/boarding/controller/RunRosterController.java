package src.backend.boarding.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.query.RosterQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadRoster;

/**
 * 매니저 앱의 승하차지별 명단(API_SPEC §4.2 {@code GET /runs/{runId}/roster}, RST-01·02·04·M-03,
 * Phase 9 목표 6·15).
 *
 * <p>회차 접근·확정 여부 판정은 전부 {@link RosterQueryService#managerRoster} 안에 있다 — 이
 * 컨트롤러는 위임만 한다({@code StaffRunAssignmentController} 와 같은 얇은 형태).
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class RunRosterController {

    private final RosterQueryService rosterQueryService;

    @CanReadRoster
    @Operation(summary = "승하차지별 명단 (RST-01·02·04, M-03)")
    @GetMapping("/{runId}/roster")
    public ApiResponse<ManagerRosterResponse> roster(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(rosterQueryService.managerRoster(requester, runId));
    }
}
