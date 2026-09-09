package src.backend.monitoring.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorAcademy;
import src.backend.monitoring.dto.StaffRunLiveResponse;
import src.backend.monitoring.query.StaffRunLiveQueryService;

/**
 * 관계자 웹의 전 차량 실시간 위치 스냅샷 API(§5.18 {@code GET /staff/runs/live}, MON-07, LOC-01).
 *
 * <p>{@code StaffRunController}(§5.10, {@code /staff/runs})와 경로 접두가 겹치지만 별도 컨트롤러다
 * — 이쪽은 좌표·진행도가 5~10초마다 갱신되는 관제 스냅샷이고, 그쪽은 배치 정보 목록이라 갱신 주기와
 * 캐시 전략이 다르다(같은 컨트롤러에 묶으면 그 차이가 메서드 단위로만 표현된다).
 */
@RestController
@RequestMapping("/staff/runs/live")
@RequiredArgsConstructor
public class StaffRunLiveController {

    private final StaffRunLiveQueryService staffRunLiveQueryService;

    /** 오늘 운행 중({@code status=moving}) 회차의 위치·진행도·지연 스냅샷. */
    @CanMonitorAcademy
    @GetMapping
    public ApiResponse<StaffRunLiveResponse> live(@AuthenticationPrincipal AuthUser requester) {
        return ApiResponse.ok(staffRunLiveQueryService.live(requester));
    }
}
