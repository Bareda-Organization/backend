package src.backend.exception.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.exception.command.EmergencyCommandService;
import src.backend.exception.dto.EmergencyCancelResponse;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.dto.EmergencyRaiseResponse;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanRaiseEmergency;

/**
 * 기사·동승자 단말의 비상 신고 발신·취소 API(EXC-04, Phase 11 T2 목표 5·8·9) —
 * {@link src.backend.run.controller.DriverRunController} 와 같이 {@code /staff} 접두어가 없는
 * 단말 표면이다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class EmergencyController {

    private final EmergencyCommandService emergencyCommandService;

    /** 비상 신고 접수(목표 5·8) — 위치는 자동 첨부, 재전송은 최초 접수 결과를 그대로 돌려준다. */
    @CanRaiseEmergency
    @Operation(summary = "비상 알림 발신·취소 (EXC-04, M-15)")
    @PostMapping("/{runId}/emergency")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EmergencyRaiseResponse> raise(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody EmergencyRaiseRequest request) {
        return ApiResponse.ok(emergencyCommandService.raise(requester, runId, request));
    }

    /** 발신 1분 이내 취소(목표 9). */
    @CanRaiseEmergency
    @Operation(summary = "비상 알림 취소 (EXC-04, M-15)")
    @DeleteMapping("/{runId}/emergency/{id}")
    public ApiResponse<EmergencyCancelResponse> cancel(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @PathVariable Long id) {
        return ApiResponse.ok(emergencyCommandService.cancel(requester, runId, id));
    }
}
