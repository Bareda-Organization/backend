package src.backend.boarding.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.GuardianPhoneResponse;
import src.backend.boarding.query.GuardianPhoneQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadRoster;

/**
 * 매니저 앱의 보호자 전화 원번호 단건 조회(API_SPEC §4.2.1 {@code GET /runs/{runId}/riders/{riderId}/guardian-phone},
 * Ruling 482·521) — 회차 접근·명단 포함 여부 판정과 감사는 {@link GuardianPhoneQueryService} 안에 있다. 이 컨트롤러는 위임만 한다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs/{runId}/riders/{riderId}")
@RequiredArgsConstructor
public class RiderGuardianPhoneController {

    private final GuardianPhoneQueryService guardianPhoneQueryService;

    /** 보호자 원번호 1건(§4.2.1) — 명단은 마스킹이라 [전화] 를 누를 때만 부른다. */
    @CanReadRoster
    @Operation(summary = "보호자 전화 원번호 (Ruling 482, M-03)")
    @GetMapping("/guardian-phone")
    public ApiResponse<GuardianPhoneResponse> guardianPhone(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @PathVariable Long riderId) {
        return ApiResponse.ok(guardianPhoneQueryService.guardianPhone(requester, runId, riderId));
    }
}
