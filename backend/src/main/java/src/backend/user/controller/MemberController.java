package src.backend.user.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import src.backend.global.security.authz.CanManageMembers;
import src.backend.student.dto.StudentResponse;
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
@CanManageMembers
public class MemberController {

    private final MemberCommandService memberCommandService;
    private final MemberQueryService memberQueryService;

    public MemberController(MemberCommandService memberCommandService, MemberQueryService memberQueryService) {
        this.memberCommandService = memberCommandService;
        this.memberQueryService = memberQueryService;
    }

    /** 구성원 등록 — 계정 생성 + 학원·역할 부여. */
    @Operation(summary = "구성원 등록(계정 생성 + 학원·역할 부여)",
            description = "관리자가 기사·**선탑자(ATTENDANT)**·학부모·학원관리자 계정을 직접 만든다. "
                    + "`tenantId` 는 학원 관리자면 생략 가능(본인 학원), 플랫폼 관리자는 필수다. "
                    + "⚠️ `PLATFORM_ADMIN` 역할로는 등록할 수 없다(권한 상승 차단, 400). "
                    + "⚠️ **학생은 여기서 만들지 않는다** — 학생은 계정(app_user)이 아니라 `student` 테이블이라 `POST /api/students` 를 쓴다.",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @PostMapping
    public ApiResponse<MemberResponse> register(@AuthenticationPrincipal AuthUser admin,
                                                @Valid @RequestBody CreateMemberRequest request) {
        return ApiResponse.ok(memberCommandService.register(admin, request));
    }

    /** 학원 구성원 목록 — role 지정 시 해당 역할만. */
    @Operation(summary = "학원 구성원 목록",
            description = "`role` 로 좁힐 수 있다(`DRIVER`·`ATTENDANT`·`PARENT`·`ACADEMY_ADMIN`). "
                    + "⚠️ `role=STUDENT` 로는 학생 명단이 나오지 않는다 — 학생은 `GET /api/students` 다. "
                    + "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @GetMapping
    public ApiResponse<List<MemberResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                  @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
                                                  @Parameter(example = "DRIVER", description = "역할 필터. 생략하면 전체") @RequestParam(required = false) Role role) {
        return ApiResponse.ok(memberQueryService.list(admin, tenantId, role));
    }

    /** 구성원 상세 — 배정된 버스·담당 학생까지 함께 준다. */
    @Operation(summary = "구성원 상세",
            description = "배정 버스·담당 학생을 함께 준다 — 화면이 '해제 가능한가'를 이 응답 하나로 판단한다. "
                    + "다른 학원 구성원 id 를 넣으면 404 다(존재 여부도 알려주지 않는다). "
                    + "예시 id 3 은 시드의 박기사(DRIVER)이며, `assignedBuses` 에 3호차가 들어 있는 것이 정상이다.",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @GetMapping("/{id}")
    public ApiResponse<MemberDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                    @Parameter(example = "3") @PathVariable Long id,
                                                    @Parameter(example = "1", description = "대상 학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(memberQueryService.get(admin, id, tenantId));
    }

    /** 구성원 수정 — 전달된 필드만 갱신한다. */
    @Operation(summary = "구성원 수정(이름·전화·사진·이메일·역할)",
            description = "전달된 필드만 갱신한다(null = 그대로). "
                    + "⚠️ 계정(app_user)은 한 벌뿐이라 이름·전화·사진은 그 사람이 속한 모든 학원에서 함께 바뀐다. "
                    + "본인 계정 수정과 PLATFORM_ADMIN 으로의 역할 변경은 거부한다(400). "
                    + "이메일은 로그인 ID라 바뀔 때만 중복 검사한다(같은 값을 그대로 보내면 통과) — 예시값이 그 경우다.",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @PatchMapping("/{id}")
    public ApiResponse<MemberResponse> update(@AuthenticationPrincipal AuthUser admin,
                                              @Parameter(example = "3") @PathVariable Long id,
                                              @Parameter(example = "1", description = "대상 학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
                                              @Valid @RequestBody UpdateMemberRequest request) {
        return ApiResponse.ok(memberCommandService.update(admin, id, tenantId, request));
    }

    /** 관리자 강제 비밀번호 재설정 — 응답에 비밀번호를 담지 않는다. */
    @Operation(summary = "비밀번호 재설정(관리자 대행)",
            description = "현재 비밀번호를 묻지 않는다. 관리자 계정(ACADEMY_ADMIN·PLATFORM_ADMIN)은 대상이 아니다(400) — "
                    + "관리자끼리 서로의 비밀번호를 바꿀 수 있으면 계정 탈취 경로가 되기 때문이다. "
                    + "⚠️ **이미 발급된 JWT 를 무효화하지 않는다** — 토큰 블랙리스트가 없어 그 사람의 기존 토큰은 만료 시각까지 그대로 살아 있다. "
                    + "'비밀번호를 바꿨으니 즉시 로그아웃된다'고 오해하기 쉬운 지점이다. "
                    + "응답에 새 비밀번호를 되돌려주지 않는다(평문이 로그·캐시에 남는다).",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @PatchMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@AuthenticationPrincipal AuthUser admin,
                                           @Parameter(example = "3") @PathVariable Long id,
                                           @Parameter(example = "1", description = "대상 학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        memberCommandService.resetPassword(admin, id, tenantId, request);
        return ApiResponse.ok(null);
    }

    /** 학원 멤버십 해제 — 계정(app_user)은 지우지 않는다. */
    @Operation(summary = "학원 멤버십 해제 (계정 삭제가 아니다)",
            description = "⚠️ **물리 삭제가 아니다** — `user_tenant_role` 행만 지우고 **계정(app_user)은 그대로 남는다.** "
                    + "그 사람이 다른 학원에도 소속돼 있으면 그쪽 멤버십은 유지되고, 여전히 로그인도 된다. "
                    + "과거 승하차 기록이 계정을 참조하므로 행을 지우면 감사 추적이 끊기기 때문이다. "
                    + "버스 배정(기사·선탑자)이나 보호자 연결이 남아 있으면 409 로 거부하고, 무엇이 막는지를 메시지에 담는다. "
                    + "⚠️ **예시값(3=박기사)을 그대로 실행하면 409 다** — 시드에서 3호차 기사로 배정돼 있기 때문이며, 그게 정상 동작이다. "
                    + "성공 응답을 보려면 `POST /api/members` 로 방금 만든(아무 데도 연결되지 않은) 구성원 id 를 넣는다. "
                    + "본인 계정은 해제할 수 없다(400).",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal AuthUser admin,
                                    @Parameter(example = "3") @PathVariable Long id,
                                    @Parameter(example = "1", description = "대상 학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        memberCommandService.removeMembership(admin, id, tenantId);
        return ApiResponse.ok(null);
    }

    /** 학부모 기준 역방향 자녀 목록 — 읽기 전용(연결 편집은 학생 쪽 한 곳에서만 한다). */
    @Operation(summary = "학부모의 자녀 목록(역방향 조회)",
            description = "이 학부모와 연결된 학생들. 형제가 다른 학원에 다니면 요청자 학원 것만 나온다. "
                    + "⚠️ 비활성(퇴원) 학생도 포함한다 — 관리자가 연결을 정리하려면 전부 보여야 한다. "
                    + "연결 추가·해제는 /api/students/{id}/guardians 쪽에서 한다. "
                    + "예시 id 2 는 시드의 이부모이며 학생 1(김민준)·2(이서연)가 나온다.",
            tags = {"00. MVP 사용 API", "03. 사용자(Member)"})
    @GetMapping("/{id}/students")
    public ApiResponse<List<StudentResponse>> students(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "2", description = "학부모 user id") @PathVariable Long id,
            @Parameter(example = "1", description = "대상 학원 id. 학원 관리자는 생략 가능(본인 학원), 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(memberQueryService.studentsOf(admin, id, tenantId));
    }
}
