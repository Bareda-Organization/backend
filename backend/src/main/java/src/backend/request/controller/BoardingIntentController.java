package src.backend.request.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanWriteIntent;
import src.backend.request.command.BoardingIntentCommandService;
import src.backend.request.dto.BoardingIntentToggleRequest;
import src.backend.request.dto.BoardingIntentToggleResponse;

/**
 * 회차별 탑승 의사 토글 API(ATT-01·02 · P-03, API_SPEC §3.6).
 *
 * <p>구간 판정({@code idle→confirmed} 30분 전 · 확정 후 · 운행 시작 후)에 따라 즉시 반영·승인
 * 대기·재최적화 없는 즉시 수용 세 갈래로 갈리는데, 그 분기는 전부 {@link BoardingIntentCommandService}
 * 안에서 이뤄진다 — 컨트롤러는 요청을 그대로 위임할 뿐이다.
 */
@Tag(name = ApiTags.PARENT_STUDENT)
@RestController
@RequestMapping("/students/{id}/runs/{runId}/intent")
@RequiredArgsConstructor
public class BoardingIntentController {

    private final BoardingIntentCommandService boardingIntentCommandService;

    /** 탑승 의사(riding)를 토글한다(§3.6 {@code PATCH}) — 응답 형태는 구간마다 다르다. */
    @CanWriteIntent
    @Operation(summary = "회차별 탑승 토글 (ATT-01·02, P-03)")
    @PatchMapping
    public ApiResponse<BoardingIntentToggleResponse> toggle(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId, @PathVariable Long runId,
            @Valid @RequestBody BoardingIntentToggleRequest request) {
        return ApiResponse.ok(boardingIntentCommandService.toggle(authUser, studentId, runId, request));
    }
}
