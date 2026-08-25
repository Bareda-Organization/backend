package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import src.backend.account.dto.MeResponse;
import src.backend.account.query.MeQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;

/** 본인 프로필 조회(API_SPEC §2.10). */
@RestController
public class MeController {

    private final MeQueryService meQueryService;

    public MeController(MeQueryService meQueryService) {
        this.meQueryService = meQueryService;
    }

    @AuthenticatedOnly
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(meQueryService.getMe(authUser.accountId()));
    }
}
