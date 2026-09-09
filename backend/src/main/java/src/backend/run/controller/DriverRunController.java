package src.backend.run.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.run.command.RunAckChangesCommandService;
import src.backend.run.command.RunArrivalCommandService;
import src.backend.run.command.RunStartCommandService;
import src.backend.run.dto.RunAckChangesResponse;
import src.backend.run.dto.RunArriveResponse;
import src.backend.run.dto.RunStartResponse;

/**
 * 기사·동승자 단말의 회차 운행 조작 API(API_SPEC §4.4·§4.5·§4.11, RUN-02·04·05·06·07 · M-04·M-10·M-11).
 *
 * <p>이 저장소에서 <b>{@code /staff} 접두어가 없는 첫 컨트롤러</b>다 — 지금까지의 {@code run} 모듈
 * 엔드포인트는 전부 관계자 웹({@code /staff/runs})이었고, 여기는 운행 중인 단말(기사·동승자)이
 * 호출하는 별도 표면이다. 학원 범위는 다른 컨트롤러와 같이 <b>토큰이 정한다</b>(§1.5) — 회차가 다른
 * 학원 소속이면 각 커맨드 서비스가 {@code 404 RUN_NOT_FOUND} 로 답한다.
 *
 * <p>배치 여부 인가({@code DRIVER_ONLY} 대 일반 {@code FORBIDDEN})는 이 컨트롤러가 아니라
 * {@link src.backend.run.access.RunAssignmentAccess} 가 각 커맨드 서비스 안에서 판정한다 — 세
 * 엔드포인트가 같은 인가 규칙을 공유하므로 컨트롤러에 애너테이션으로 얹는 대신 서비스 계층에 모았다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class DriverRunController {

    private final RunStartCommandService runStartCommandService;

    private final RunArrivalCommandService runArrivalCommandService;

    private final RunAckChangesCommandService runAckChangesCommandService;

    /** 운행 시작 처리(§4.4, RUN-02·M-10). */
    @Operation(summary = "운행모드 시작 (RUN-02, M-10)")
    @PostMapping("/{runId}/start")
    public ApiResponse<RunStartResponse> start(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(runStartCommandService.start(requester, runId));
    }

    /** 승하차지 도착 처리(§4.5, RUN-04·M-11). */
    @Operation(summary = "승하차지 도착 처리 (RUN-04, M-11)")
    @PostMapping("/{runId}/stops/{stopId}/arrive")
    public ApiResponse<RunArriveResponse> arrive(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @PathVariable Long stopId) {
        return ApiResponse.ok(runArrivalCommandService.arrive(requester, runId, stopId));
    }

    /** 노선 변경 확인 응답(§4.11, RUN-07·M-04) — {@code change_ids[]} 는 요청에 와도 쓰지 않는다(전건 확인). */
    @Operation(summary = "노선 변경 확인 응답 (RUN-07, M-04)")
    @PostMapping("/{runId}/ack-changes")
    public ApiResponse<RunAckChangesResponse> ackChanges(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId) {
        return ApiResponse.ok(runAckChangesCommandService.ackChanges(requester, runId));
    }
}
