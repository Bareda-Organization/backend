package src.backend.run.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageSchedule;
import src.backend.run.command.ForcedAdditionCommandService;
import src.backend.run.dto.ForcedAdditionRequest;
import src.backend.run.dto.ForcedAdditionResponse;

/**
 * 관계자 웹의 ①구간 강제 추가 API(RTE-06, API_SPEC §5.7, Ruling 197).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 다른 학원의 회차를 {@code {runId}} 로 지목하면
 * {@code 404 RUN_NOT_FOUND} 다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffForcedAdditionController {

    private final ForcedAdditionCommandService forcedAdditionCommandService;

    /**
     * ①구간 강제 추가(§5.7) — ②③구간은 {@code 403 CHANGE_WINDOW_CLOSED}, 정원 초과는
     * {@code 409 CAPACITY_EXCEEDED}, 주소 검증 실패는 {@code 422 ADDRESS_VERIFICATION_FAILED}.
     */
    @CanManageSchedule
    @Operation(summary = "노선 강제 추가 (RTE-06, A-06)")
    @PostMapping("/{runId}/forced-add")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ForcedAdditionResponse> add(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody ForcedAdditionRequest request) {
        return ApiResponse.ok(forcedAdditionCommandService.add(requester, runId, request));
    }
}
