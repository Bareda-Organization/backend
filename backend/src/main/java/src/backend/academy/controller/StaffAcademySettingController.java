package src.backend.academy.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.AcademySettingCommandService;
import src.backend.academy.dto.AcademySettingResponse;
import src.backend.academy.dto.AcademySettingUpdateRequest;
import src.backend.academy.query.AcademySettingQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;

/**
 * 학원별 설정 조회·수정 API(API_SPEC §5.21, Phase 11 목표 4) — 학원 관계자 전용이지만 그 판정은
 * {@code hasAuthority(...)} 애너테이션이 아니라 서비스 계층({@link AcademySettingQueryService}·
 * {@link AcademySettingCommandService})이 직접 한다 — {@code Permissions} 카탈로그가 이 값을
 * 권한 상수로 매핑하지 않았기 때문이다(각 서비스 자바독 참고). 이 컨트롤러는 {@code @AuthenticatedOnly}
 * 로 인증 여부만 확인한다.
 */
@RestController
@RequestMapping("/staff/academy-settings")
@RequiredArgsConstructor
public class StaffAcademySettingController {

    private final AcademySettingQueryService academySettingQueryService;

    private final AcademySettingCommandService academySettingCommandService;

    @AuthenticatedOnly
    @GetMapping
    public ApiResponse<AcademySettingResponse> get(@AuthenticationPrincipal AuthUser requester) {
        return ApiResponse.ok(academySettingQueryService.get(requester));
    }

    /** §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @AuthenticatedOnly
    @PatchMapping
    public ApiResponse<AcademySettingResponse> update(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody AcademySettingUpdateRequest request) {
        return ApiResponse.ok(academySettingCommandService.update(requester, request));
    }
}
