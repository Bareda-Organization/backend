package src.backend.exception.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.exception.command.NoShowContactCommandService;
import src.backend.exception.dto.NoShowContactRequest;
import src.backend.exception.dto.NoShowContactResponse;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;

/**
 * 미승차 연락 시도 기록 API(API_SPEC §4.8) — 동승자 전용이지만 그 판정은
 * {@code hasAuthority(...)} 애너테이션이 아니라 {@link NoShowContactCommandService} 안에서 한다
 * ({@code BoardingController} 와 같은 근거 — {@code 403 ESCORT_ONLY} 를 일반 {@code FORBIDDEN} 과
 * 구별하기 위함). 이 컨트롤러는 {@link AuthenticatedOnly} 로 인증 여부만 확인한다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs/{runId}/riders/{riderId}/no-show-contacts")
@RequiredArgsConstructor
public class NoShowContactController {

    private final NoShowContactCommandService noShowContactCommandService;

    /** 연락 시도 기록(§4.8) — 동승자 여부 판정은 {@link NoShowContactCommandService} 안에서 한다. 같은 {@code client_key} 재전송은 최초 결과를 200 으로 돌려준다(§1.7). */
    @AuthenticatedOnly
    @Operation(summary = "미승차 연락 시도 기록 (EXC-01)")
    @PostMapping
    public ResponseEntity<ApiResponse<NoShowContactResponse>> recordAttempt(
            @AuthenticationPrincipal AuthUser authUser, @PathVariable Long runId, @PathVariable Long riderId,
            @Valid @RequestBody NoShowContactRequest request) {
        NoShowContactResponse response = noShowContactCommandService.recordAttempt(authUser, runId, riderId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }
}
