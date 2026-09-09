package src.backend.student.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageStudent;
import src.backend.global.security.authz.CanReadStudentRecord;
import src.backend.student.command.StudentCommandService;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentListRequest;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentSummaryResponse;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.dto.StudentWithdrawalResponse;
import src.backend.student.photo.spec.StudentPhoto;
import src.backend.student.query.StudentQueryService;

/**
 * 관계자 웹의 학생 관리 API(STU-01~04 · 07 · 08 · A-10, API_SPEC §5.11).
 *
 * <p>소속 학원은 토큰이 정한다(§1.5) — 요청에 어느 학원인지 지정할 자리가 부재하다.
 *
 * <p>읽기와 쓰기의 인가 애너테이션이 갈려 있다 — 학생 정보를 읽는 역할(관계자 · 메인 관리자)과
 * 쓰는 역할(관계자)이 다르기 때문이다(FEATURE_SPEC §6.2).
 *
 * <p><b>등록·수정만 {@code multipart/form-data} 다</b> — 이 저장소에서 유일한 예외이고 사양이 그렇게
 * 정했다(§1.1). 나머지 항목은 JSON 파트 {@code data}, 사진은 파일 파트 {@code photo} 로 온다.
 *
 * <p>매핑에 {@code consumes} 를 걸지 않는다 — 걸면 형식이 맞지 않는 요청이 <b>핸들러 매핑 단계</b>에서
 * {@code 415} 로 끊겨 계정 상태 게이트({@code preHandle})보다 앞서고, 승인 대기 계정이 받아야 할
 * {@code 403 AUTH_PENDING} 이 {@code 415} 로 뒤바뀐다(API_SPEC §1.4).
 *
 * <p>계정 상태 게이트 애너테이션({@code @AllowedWhenPending} 등)은 붙지 않는다 — 승인 대기·거절
 * 계정이 학생 명단에 닿을 이유가 부재하므로, 허용 목록 밖으로 남아 {@code 403} 이 되는 것이 사양이다
 * (API_SPEC §1.4).
 */
@RestController
@RequestMapping("/staff/students")
@RequiredArgsConstructor
public class StaffStudentController {

    private final StudentQueryService studentQueryService;

    private final StudentCommandService studentCommandService;

    /** 학생 목록·검색(STU-01, §5.11) — 강제 추가 자동완성과 공용이다. */
    @CanReadStudentRecord
    @GetMapping
    public ApiResponse<PageResponse<StudentSummaryResponse>> list(@AuthenticationPrincipal AuthUser authUser,
            @ModelAttribute StudentListRequest request) {
        return ApiResponse.ok(studentQueryService.list(authUser, request));
    }

    /** 학생 상세(STU-01, §5.11). */
    @CanReadStudentRecord
    @GetMapping("/{id}")
    public ApiResponse<StudentDetailResponse> detail(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long id) {
        return ApiResponse.ok(studentQueryService.detail(authUser, id));
    }

    /**
     * 학생 등록(STU-02 · 07 · 08, §5.11).
     *
     * <p>등록과 조회를 나눠 부르는 이유는 §1.9 가 "변경 후 자원 상태를 그대로 반환" 을 요구하는데, 그
     * 상태에 <b>쓰기가 만들지 않는 값</b>(보호자 연락처)이 함께 들어가기 때문이다 — 쓰기 쪽이 그것까지
     * 조립하면 같은 조인이 두 곳에 생긴다.
     */
    @CanManageStudent
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StudentDetailResponse> register(@AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestPart("data") StudentRegisterRequest request,
            @RequestPart(name = "photo", required = false) MultipartFile photo) {
        return ApiResponse.ok(studentQueryService.detail(authUser,
                studentCommandService.register(authUser, request, photoOf(photo))));
    }

    /** 학생 정보 수정(STU-03 · 07 · 08, §5.11) — 주소·보호자 연락처는 대상 밖이다(A-10). */
    @CanManageStudent
    @PatchMapping("/{id}")
    public ApiResponse<StudentDetailResponse> update(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long id, @Valid @RequestPart("data") StudentUpdateRequest request,
            @RequestPart(name = "photo", required = false) MultipartFile photo) {
        return ApiResponse.ok(studentQueryService.detail(authUser,
                studentCommandService.update(authUser, id, request, photoOf(photo))));
    }

    /** 퇴원 처리(STU-04, §5.11) — soft delete 이며 오늘 명단은 유지된다. */
    @CanManageStudent
    @DeleteMapping("/{id}")
    public ApiResponse<StudentWithdrawalResponse> withdraw(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long id) {
        return ApiResponse.ok(studentCommandService.withdraw(authUser, id));
    }

    /**
     * 파일 파트를 검증된 사진으로 바꾼다 — 서블릿 타입은 여기까지고 아래로 내려가지 않는다.
     *
     * <p>파트를 <b>비워서</b> 보낸 것과 아예 보내지 않은 것을 같게 다룬다. 브라우저 폼은 파일을 고르지
     * 않아도 빈 파트를 실어 보내는데, 그것을 갈라 다루면 같은 화면의 같은 조작이 서버에서 두 갈래가 된다.
     */
    private static StudentPhoto photoOf(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return null;
        }
        try {
            return StudentPhoto.of(photo.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 사진을 읽지 못했습니다", e);
        }
    }
}
