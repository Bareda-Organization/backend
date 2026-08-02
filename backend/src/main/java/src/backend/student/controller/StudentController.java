package src.backend.student.controller;

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
import src.backend.student.dto.CreateStudentRequest;
import src.backend.student.dto.LinkGuardianRequest;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentResponse;
import src.backend.student.dto.UpdateDropoffRequest;
import src.backend.student.dto.UpdateStudentAssignmentRequest;
import src.backend.student.dto.UpdateStudentRequest;
import src.backend.student.command.StudentCommandService;
import src.backend.student.query.StudentQueryService;

/**
 * 학생 관리 API(관리자 전용). 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
@Tag(name = "04. 학생(Student)",
        description = "학생 등록·인적정보 수정·퇴원(비활성) 처리·배정(버스/정류장)·하차지 설정·보호자 연결/해제. 관리자 전용. "
                + "⚠️ 퇴원은 행을 지우지 않고 active=false 로만 바꾼다 — 목록은 기본적으로 활성 학생만 준다.")
@RestController
@RequestMapping("/api/students")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class StudentController {

    private final StudentCommandService studentCommandService;
    private final StudentQueryService studentQueryService;

    public StudentController(StudentCommandService studentCommandService, StudentQueryService studentQueryService) {
        this.studentCommandService = studentCommandService;
        this.studentQueryService = studentQueryService;
    }

    /** 학생 등록 — 배정·보호자 연결 포함. */
    @Operation(summary = "학생 등록",
            description = "⚠️ **학생은 로그인 계정(app_user)을 만들지 않는다** — `student` 테이블 행일 뿐이라 `POST /api/members` 와 별개다. "
                    + "학생 앱이 없어 로그인할 화면이 없기 때문이다. "
                    + "등록과 동시에 버스·승차 정류장 배정과 보호자 연결을 함께 지정할 수 있다. "
                    + "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @PostMapping
    public ApiResponse<StudentDetailResponse> create(@AuthenticationPrincipal AuthUser admin,
                                                     @Valid @RequestBody CreateStudentRequest request) {
        return ApiResponse.ok(studentCommandService.create(admin, request));
    }

    /** 학원 학생 목록 — 기본은 재원(활성) 학생만. */
    @Operation(summary = "학생 목록",
            description = "⚠️ **기본값은 활성(재원) 학생만**이다. 퇴원 처리된 학생까지 보려면 `includeInactive=true` 를 명시해야 한다. "
                    + "기본을 '전부'로 두면 퇴원생이 배차·명단·시뮬레이션에 계속 끼어든다. "
                    + "'학생을 지웠는데 목록에 아직 있다'가 아니라 '지운 게 아니라 비활성이고, 목록은 기본적으로 걸러 준다'가 맞다. "
                    + "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @GetMapping
    public ApiResponse<List<StudentResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                   @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
                                                   @Parameter(example = "false", description = "true 로 주면 퇴원(active=false) 학생도 함께 나온다") @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(studentQueryService.list(admin, tenantId, includeInactive));
    }

    /** 학생 상세 — 버스·정류장·보호자 포함. */
    @Operation(summary = "학생 상세",
            description = "배정 버스·기본 승차 정류장·등하원 좌표·연결된 보호자를 함께 준다. 비활성 학생도 id 로는 조회된다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @GetMapping("/{id}")
    public ApiResponse<StudentDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                     @Parameter(example = "1", description = "학생 id(1=김민준)") @PathVariable Long id) {
        return ApiResponse.ok(studentQueryService.get(admin, id));
    }

    /** 인적 정보 수정 — 이름·전화·사진. 배차·하차지는 아래 전용 PATCH 가 담당한다. */
    @Operation(summary = "학생 인적 정보 수정",
            description = "전달한 필드만 갱신한다(생략하면 그대로). 이름·전화·사진은 노선에 영향이 없어 "
                    + "노선 재계획을 트리거하지 않는다 — 배차·하차지 변경은 각각 `/assignment` · `/dropoff` 를 쓴다. "
                    + "`photoUrl` 은 URL 문자열만 저장한다(업로드 API·파일 스토리지는 범위 밖).",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @PatchMapping("/{id}")
    public ApiResponse<StudentResponse> updateProfile(@AuthenticationPrincipal AuthUser admin,
                                                      @Parameter(example = "1") @PathVariable Long id,
                                                      @Valid @RequestBody UpdateStudentRequest request) {
        return ApiResponse.ok(studentCommandService.updateProfile(admin, id, request));
    }

    /** 퇴원 처리 — 행을 지우지 않고 비활성으로 바꾼다. 갱신된 상태를 돌려준다. */
    @Operation(summary = "퇴원 처리(비활성화) — 삭제가 아니다",
            description = "⚠️ **물리 삭제가 아니다** — `active=false` 로만 바꾼다. 과거 승하차 기록이 이 학생을 참조하므로 "
                    + "행을 지우면 감사 추적이 끊긴다. 배정 버스도 지우지 않는다(어느 버스에서 빠졌는지 남긴다) — "
                    + "명단·배차·시뮬레이션에서 빠지는 것은 조회 계층이 보장한다. "
                    + "⚠️ **응답이 204 No Content 가 아니다** — 갱신된 `StudentResponse`(`active=false` 가 찍힌 학생 정보)를 200 으로 돌려준다. "
                    + "화면이 재조회 없이 목록의 그 줄을 바로 회색 처리할 수 있게 하기 위해서다. "
                    + "이미 비활성인 학생을 다시 부르면 409 다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @DeleteMapping("/{id}")
    public ApiResponse<StudentResponse> deactivate(@AuthenticationPrincipal AuthUser admin,
                                                   @Parameter(example = "1") @PathVariable Long id) {
        return ApiResponse.ok(studentCommandService.deactivate(admin, id));
    }

    /** 재배정 — 담당 버스·기본 승차 정류장. */
    @Operation(summary = "학생 배차 변경(버스·승차 정류장)",
            description = "노선에 영향이 있는 변경이라 재계획 신호를 발생시킨다. 전달한 필드만 바꾼다(생략 = 그대로). "
                    + "예시값은 시드의 현재 배정과 같아 그대로 실행해도 안전하다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @PatchMapping("/{id}/assignment")
    public ApiResponse<StudentResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                               @Parameter(example = "1", description = "학생 id(1=김민준)") @PathVariable Long id,
                                               @Valid @RequestBody UpdateStudentAssignmentRequest request) {
        return ApiResponse.ok(studentCommandService.updateAssignment(admin, id, request));
    }

    /** 하차지(하원) 좌표 설정 — routing 모듈이 하원 노선 계산에 사용. */
    @Operation(summary = "하차지(하원) 좌표 설정",
            description = "하원 노선 계산의 정차 좌표가 된다. ⚠️ 학생 **자신의 컬럼**만 바꾼다 — 정류장(`Stop`) 행은 건드리지 않는다. "
                    + "정류장은 여러 학생이 공유하는 행이라 그 좌표를 고치면 같은 정류장을 쓰는 다른 학생까지 끌려간다. "
                    + "학부모가 스스로 바꾸는 경로는 `POST /api/location-change-requests` 쪽이다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @PatchMapping("/{id}/dropoff")
    public ApiResponse<StudentResponse> updateDropoff(@AuthenticationPrincipal AuthUser admin,
                                                       @Parameter(example = "1", description = "학생 id(1=김민준)") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateDropoffRequest request) {
        return ApiResponse.ok(studentCommandService.updateDropoff(admin, id, request));
    }

    /** 보호자 연결 추가. */
    @Operation(summary = "보호자 연결 추가(학생 ↔ 학부모 매칭)",
            description = "그 학원의 `PARENT` 계정만 연결할 수 있다. 한 학생에 여러 보호자, 한 보호자에 여러 학생(형제자매)이 가능하다. "
                    + "이미 연결된 조합이면 409 다 — 그래서 예시는 학생 **3(박도윤)** 이다. "
                    + "시드에서 학생 1·2 는 이미 학부모 2 와 연결돼 있어 그대로 실행하면 409 가 난다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @PostMapping("/{id}/guardians")
    public ApiResponse<StudentDetailResponse> addGuardian(@AuthenticationPrincipal AuthUser admin,
                                                          @Parameter(example = "3", description = "학생 id(3=박도윤, 시드에서 아직 보호자 미연결)") @PathVariable Long id,
                                                          @Valid @RequestBody LinkGuardianRequest request) {
        return ApiResponse.ok(studentCommandService.addGuardian(admin, id, request));
    }

    /** 보호자 연결 해제 — 연결 행만 지운다. */
    @Operation(summary = "보호자 연결 해제",
            description = "⚠️ **물리 삭제가 아니다** — `student_guardian` 연결 행만 지우고 학부모 계정도 학생도 그대로 남는다. "
                    + "보호자가 0명이 되는 것도 허용한다(학원이 직접 인계하는 경우). "
                    + "204 가 아니라 갱신된 학생 상세를 200 으로 돌려주므로 화면이 재조회 없이 목록을 다시 그린다. "
                    + "연결돼 있지 않은 조합이면 404 다. 예시값(학생 1 ↔ 학부모 2)은 시드에 실제로 있는 연결이다.",
            tags = {"00. MVP 사용 API", "04. 학생(Student)"})
    @DeleteMapping("/{id}/guardians/{guardianUserId}")
    public ApiResponse<StudentDetailResponse> removeGuardian(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "학생 id(1=김민준)") @PathVariable Long id,
            @Parameter(example = "2", description = "보호자 user id(2=이부모)") @PathVariable Long guardianUserId) {
        return ApiResponse.ok(studentCommandService.removeGuardian(admin, id, guardianUserId));
    }
}
