package src.backend.routing.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageSchedule;
import src.backend.routing.command.WaypointCommandService;
import src.backend.routing.dto.WaypointRequest;
import src.backend.routing.dto.WaypointResponse;

/**
 * 관계자 웹의 강제 경유 지점 지정·제거 API(RTE-10, API_SPEC §5.15).
 *
 * <p>{@code apply} 는 요청 본문(POST)·쿼리 파라미터(DELETE) 로 갈리지만 의미는 같다 — {@code false}
 * 는 재최적화 결과를 미리 보여주기만 하고 확정 노선은 그대로 두며, {@code true} 라야 새 노선 버전을
 * 배포하고 {@code route_changed} 를 알린다. <b>양쪽 다 응답 상태 코드는 {@code 200} 하나다</b> — 이
 * 엔드포인트의 1차 산출물은 "자원을 만들었는가" 가 아니라 "재최적화 대조표" 라, {@code apply} 값에
 * 따라 코드를 갈라도 얻는 정보가 없다(응답 본문의 {@code applied} 가 그 구분을 이미 담당한다).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 다른 학원의 회차·경유 지점을 지목하면 각각
 * {@code 404 RUN_NOT_FOUND}·{@code 404 WAYPOINT_NOT_FOUND} 다.
 */
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffWaypointController {

    private final WaypointCommandService waypointCommandService;

    /**
     * 새 경유 지점 지정(§5.15) — 운행 시작 후는 {@code 403 CHANGE_WINDOW_CLOSED}, 주소·좌표 둘 다
     * 없으면 {@code 422 VALIDATION_FAILED}.
     */
    @CanManageSchedule
    @PostMapping("/{runId}/waypoints")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<WaypointResponse> add(@AuthenticationPrincipal AuthUser requester, @PathVariable Long runId,
            @Valid @RequestBody WaypointRequest request) {
        return ApiResponse.ok(waypointCommandService.add(requester, runId, request));
    }

    /**
     * 배포된 경유 지점 제거(§5.15) — POST 와 같은 미리보기→배포 절차다. {@code apply} 기본값은
     * {@code false}(미리보기) — 실수로 즉시 배포되는 것을 막는다.
     */
    @CanManageSchedule
    @DeleteMapping("/{runId}/waypoints/{waypointId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<WaypointResponse> remove(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @PathVariable Long waypointId,
            @RequestParam(defaultValue = "false") boolean apply) {
        return ApiResponse.ok(waypointCommandService.remove(requester, runId, waypointId, apply));
    }
}
