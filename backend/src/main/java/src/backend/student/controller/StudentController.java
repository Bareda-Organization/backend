package src.backend.student.controller;

import java.util.List;

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
import src.backend.student.dto.UpdateStudentAssignmentRequest;
import src.backend.student.service.spec.StudentService;

/**
 * 학생 관리 API(관리자 전용). 학원 격리는 서비스 계층(TenantGuard)에서 검사한다.
 */
@RestController
@RequestMapping("/api/students")
@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    /** 학생 등록 — 배정·보호자 연결 포함. */
    @PostMapping
    public ApiResponse<StudentDetailResponse> create(@AuthenticationPrincipal AuthUser admin,
                                                     @Valid @RequestBody CreateStudentRequest request) {
        return ApiResponse.ok(studentService.create(admin, request));
    }

    /** 학원 학생 목록. */
    @GetMapping
    public ApiResponse<List<StudentResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                   @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(studentService.list(admin, tenantId));
    }

    /** 학생 상세 — 버스·정류장·보호자 포함. */
    @GetMapping("/{id}")
    public ApiResponse<StudentDetailResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                     @PathVariable Long id) {
        return ApiResponse.ok(studentService.get(admin, id));
    }

    /** 재배정 — 담당 버스·기본 승차 정류장. */
    @PatchMapping("/{id}/assignment")
    public ApiResponse<StudentResponse> assign(@AuthenticationPrincipal AuthUser admin,
                                               @PathVariable Long id,
                                               @Valid @RequestBody UpdateStudentAssignmentRequest request) {
        return ApiResponse.ok(studentService.updateAssignment(admin, id, request));
    }

    /** 보호자 연결 추가. */
    @PostMapping("/{id}/guardians")
    public ApiResponse<StudentDetailResponse> addGuardian(@AuthenticationPrincipal AuthUser admin,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody LinkGuardianRequest request) {
        return ApiResponse.ok(studentService.addGuardian(admin, id, request));
    }
}
