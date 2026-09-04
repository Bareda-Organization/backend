package src.backend.run.controller;

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

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.run.command.DelayNotificationCommandService;
import src.backend.run.dto.DelayRequest;
import src.backend.run.dto.DelayResponse;

/**
 * 지연 알림 신고(NTF-06, API_SPEC §4.9). 인가 판정(동승자 여부·배치 여부)은 이 컨트롤러가 아니라
 * {@link src.backend.run.access.RunAssignmentAccess} 가 커맨드 서비스 안에서 한다 —
 * {@link DriverRunController} 와 같은 규약(그 클래스 자바독 참조).
 */
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class DelayNotificationController {

    private final DelayNotificationCommandService delayNotificationCommandService;

    @PostMapping("/{runId}/delay")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DelayResponse> notifyDelay(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody DelayRequest request) {
        return ApiResponse.ok(delayNotificationCommandService.notifyDelay(requester, runId, request));
    }
}
