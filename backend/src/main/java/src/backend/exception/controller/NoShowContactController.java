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

import src.backend.exception.command.NoShowContactCommandService;
import src.backend.exception.dto.NoShowContactRequest;
import src.backend.exception.dto.NoShowContactResponse;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;

/**
 * 미승차 연락 시도 기록 API(API_SPEC §4.8) — 동승자 전용이지만 그 판정은
 * {@code hasAuthority(...)} 애너테이션이 아니라 {@link NoShowContactCommandService} 안에서 한다
 * ({@code BoardingController} 와 같은 근거 — {@code 403 ESCORT_ONLY} 를 일반 {@code FORBIDDEN} 과
 * 구별하기 위함). 이 컨트롤러는 {@link AuthenticatedOnly} 로 인증 여부만 확인한다.
 */
@RestController
@RequestMapping("/runs/{runId}/riders/{riderId}/no-show-contacts")
@RequiredArgsConstructor
public class NoShowContactController {

    private final NoShowContactCommandService noShowContactCommandService;

    @AuthenticatedOnly
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoShowContactResponse> recordAttempt(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long runId, @PathVariable Long riderId, @Valid @RequestBody NoShowContactRequest request) {
        return ApiResponse.ok(noShowContactCommandService.recordAttempt(authUser, runId, riderId, request));
    }
}
