package src.backend.user.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.user.dto.CreateMemberRequest;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.command.MemberCommandService;
import src.backend.user.query.MemberQueryService;

/**
 * 구성원(기사·학부모·학생·학원관리자) 관리 API — 관리자가 계정+멤버십을 등록한다.
 * 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class MemberController {

    private final MemberCommandService memberCommandService;
    private final MemberQueryService memberQueryService;

    public MemberController(MemberCommandService memberCommandService, MemberQueryService memberQueryService) {
        this.memberCommandService = memberCommandService;
        this.memberQueryService = memberQueryService;
    }

    /** 구성원 등록 — 계정 생성 + 학원·역할 부여. */
    @PostMapping
    public ApiResponse<MemberResponse> register(@AuthenticationPrincipal AuthUser admin,
                                                @Valid @RequestBody CreateMemberRequest request) {
        return ApiResponse.ok(memberCommandService.register(admin, request));
    }

    /** 학원 구성원 목록 — role 지정 시 해당 역할만. */
    @GetMapping
    public ApiResponse<List<MemberResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                  @RequestParam(required = false) Long tenantId,
                                                  @RequestParam(required = false) Role role) {
        return ApiResponse.ok(memberQueryService.list(admin, tenantId, role));
    }
}
