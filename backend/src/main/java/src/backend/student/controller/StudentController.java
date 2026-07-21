package src.backend.student.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import src.backend.student.command.StudentCommandService;
import src.backend.student.query.StudentQueryService;

/**
 * 학생 관리 API(관리자 전용). 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
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
    @PostMapping
    public ApiResponse<StudentDetailResponse> create(@AuthenticationPrincipal AuthUser admin,
                                                     @Valid @RequestBody CreateStudentRequest request) {
        return ApiResponse.ok(studentCommandService.create(admin, request));
    }

    /** 학원 학생 목록. */
    @GetMapping
    public ApiResponse<List<StudentResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                   @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(studentQueryService.list(admin, tenantId));
    }

    /** 학생 상세 — 버스·정류장·보호자 포함. */
    @GetMapping("/{id}")
    public ApiResponse<StudentDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                     @Parameter(example = "1") @PathVariable Long id) {
        return ApiResponse.ok(studentQueryService.get(admin, id));
    }

    /** 재배정 — 담당 버스·기본 승차 정류장. */
    @PatchMapping("/{id}/assignment")
    public ApiResponse<StudentResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                               @Parameter(example = "1") @PathVariable Long id,
                                               @Valid @RequestBody UpdateStudentAssignmentRequest request) {
        return ApiResponse.ok(studentCommandService.updateAssignment(admin, id, request));
    }

    /** 하차지(하원) 좌표 설정 — routing 모듈이 하원 노선 계산에 사용. */
    @PatchMapping("/{id}/dropoff")
    public ApiResponse<StudentResponse> updateDropoff(@AuthenticationPrincipal AuthUser admin,
                                                       @Parameter(example = "1") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateDropoffRequest request) {
        return ApiResponse.ok(studentCommandService.updateDropoff(admin, id, request));
    }

    /** 보호자 연결 추가. */
    @PostMapping("/{id}/guardians")
    public ApiResponse<StudentDetailResponse> addGuardian(@AuthenticationPrincipal AuthUser admin,
                                                          @Parameter(example = "1") @PathVariable Long id,
                                                          @Valid @RequestBody LinkGuardianRequest request) {
        return ApiResponse.ok(studentCommandService.addGuardian(admin, id, request));
    }
}
