package src.backend.exception.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.exception.command.ExceptionReportCommandService;
import src.backend.exception.dto.ExceptionReportCreateRequest;
import src.backend.exception.dto.ExceptionReportCreateResponse;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;

/**
 * 기사·동승자 단말의 현장 예외 보고 등록 API(API_SPEC §4.13, EXC-02·03).
 *
 * <p>{@link DriverRunController} 와 같은 표면이다 — {@code /staff} 접두어가 없고, 배치 여부 인가
 * ({@code DRIVER_ONLY}/{@code ESCORT_ONLY} 대 일반 {@code FORBIDDEN})는 컨트롤러가 아니라
 * {@link src.backend.run.access.RunAssignmentAccess} 가 커맨드 서비스 안에서 판정한다.
 */
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class RunReportController {

    private final ExceptionReportCommandService exceptionReportCommandService;

    /** 현장 예외 상황 보고(§4.13, EXC-02·03) — {@code type=guardian_absent} 는 {@code rider_id} 필수. */
    @PostMapping("/{runId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExceptionReportCreateResponse> report(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody ExceptionReportCreateRequest request) {
        return ApiResponse.ok(exceptionReportCommandService.report(requester, runId, request));
    }
}
