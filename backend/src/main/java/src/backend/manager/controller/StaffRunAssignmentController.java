package src.backend.manager.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageManager;
import src.backend.manager.command.AssignmentCommandService;
import src.backend.manager.dto.AssignmentRequest;
import src.backend.manager.dto.RunAssignmentResponse;

/**
 * 관계자 웹의 회차별 매니저 배치 API(MGR-05·06 · A-12, API_SPEC §5.14).
 *
 * <p>경로는 {@code /staff/runs} 아래지만 권한은 {@code MANAGER_MANAGE} 다 — FEATURE_SPEC §6.2 가
 * "매니저(기사·동승자) 등록·<b>배치</b>" 를 그 권한의 설명으로 적는다. 경로가 회차라는 이유로
 * {@code SCHEDULE_MANAGE} 에 묶으면 권한 카탈로그와 코드가 갈린다.
 *
 * <p><b>충돌은 경고이고 차단이 부재하다</b>(Ruling 152) — 저장은 되고 판정 결과가
 * {@code warnings[]} 에 실린다.
 */
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffRunAssignmentController {

    private final AssignmentCommandService assignmentCommandService;

    /** 회차에 기사·동승자를 배치한다(MGR-05·06, §5.14) — 지정하지 않은 자리는 그대로 둔다. */
    @CanManageManager
    @PatchMapping("/{runId}/assignment")
    public ApiResponse<RunAssignmentResponse> assign(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(assignmentCommandService.assign(requester, runId, request));
    }
}
