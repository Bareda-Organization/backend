package src.backend.user.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.user.command.MemberCommandService;
import src.backend.user.dto.CreateMemberRequest;
import src.backend.user.dto.MemberDetailResponse;
import src.backend.user.dto.MemberResponse;
import src.backend.user.dto.ResetPasswordRequest;
import src.backend.user.dto.UpdateMemberRequest;
import src.backend.user.entity.Role;
import src.backend.user.query.MemberQueryService;

/**
 * 구성원(기사·선탑자·학부모·학원관리자) 관리 API — 관리자가 계정+멤버십을 등록·수정·해제한다.
 * 학원 격리는 서비스 계층(TenantGuard + 멤버십 우선 조회)에서 검사한다.
 *
 * <p>학생은 여기 들어가지 않는다 — 학생은 app_user 가 아니라 student 테이블이라 /api/students 를 쓴다.
 */
@Tag(name = "03. 사용자(Member)",
        description = "학원 소속 계정(기사·선탑자·학부모·관리자) 등록·조회·수정·해제. 선탑자(ATTENDANT) 포함. 관리자 전용.")
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
                                                  @Parameter(example = "1") @RequestParam(required = false) Long tenantId,
                                                  @Parameter(example = "DRIVER") @RequestParam(required = false) Role role) {
        return ApiResponse.ok(memberQueryService.list(admin, tenantId, role));
    }

    /** 구성원 상세 — 배정된 버스·담당 학생까지 함께 준다. */
    @Operation(summary = "구성원 상세",
            description = "배정 버스·담당 학생을 함께 준다 — 화면이 '해제 가능한가'를 이 응답 하나로 판단한다. "
                    + "다른 학원 구성원 id 를 넣으면 404 다(존재 여부도 알려주지 않는다).")
    @GetMapping("/{id}")
    public ApiResponse<MemberDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                    @Parameter(example = "3") @PathVariable Long id) {
        return ApiResponse.ok(memberQueryService.get(admin, id));
    }

    /** 구성원 수정 — 전달된 필드만 갱신한다. */
    @Operation(summary = "구성원 수정(이름·전화·사진·이메일·역할)",
            description = "전달된 필드만 갱신한다(null = 그대로). "
                    + "⚠️ 계정(app_user)은 한 벌뿐이라 이름·전화·사진은 그 사람이 속한 모든 학원에서 함께 바뀐다. "
                    + "본인 계정 수정과 PLATFORM_ADMIN 으로의 역할 변경은 거부한다(400).")
    @PatchMapping("/{id}")
    public ApiResponse<MemberResponse> update(@AuthenticationPrincipal AuthUser admin,
                                              @Parameter(example = "3") @PathVariable Long id,
                                              @Valid @RequestBody UpdateMemberRequest request) {
        return ApiResponse.ok(memberCommandService.update(admin, id, request));
    }

    /** 관리자 강제 비밀번호 재설정 — 응답에 비밀번호를 담지 않는다. */
    @Operation(summary = "비밀번호 재설정(관리자 대행)",
            description = "현재 비밀번호를 묻지 않는다. 관리자 계정(ACADEMY_ADMIN·PLATFORM_ADMIN)은 대상이 아니다(400). "
                    + "⚠️ 이미 발급된 JWT 는 즉시 만료되지 않는다 — 토큰 무효화 장치가 없어 만료 시각까지 유효하다.")
    @PatchMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@AuthenticationPrincipal AuthUser admin,
                                           @Parameter(example = "3") @PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        memberCommandService.resetPassword(admin, id, request);
        return ApiResponse.ok(null);
    }

    /** 학원 멤버십 해제 — 계정(app_user)은 지우지 않는다. */
    @Operation(summary = "학원 멤버십 해제",
            description = "⚠️ 물리 삭제가 아니다 — user_tenant_role 행만 지우고 계정(app_user)은 남긴다. "
                    + "과거 승하차 기록이 계정을 참조하므로 지우면 감사 추적이 끊긴다. "
                    + "버스 배정·보호자 연결이 남아 있으면 409 로 거부하고 무엇이 막는지 메시지에 담는다.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal AuthUser admin,
                                    @Parameter(example = "3") @PathVariable Long id) {
        memberCommandService.removeMembership(admin, id);
        return ApiResponse.ok(null);
    }
}
