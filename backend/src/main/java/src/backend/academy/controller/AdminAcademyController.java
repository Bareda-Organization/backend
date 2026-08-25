package src.backend.academy.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.AcademyCommandService;
import src.backend.academy.dto.AcademyDetailResponse;
import src.backend.academy.dto.AcademyListRequest;
import src.backend.academy.dto.AcademyRegisterRequest;
import src.backend.academy.dto.AcademyRegisterResponse;
import src.backend.academy.dto.AcademySummaryResponse;
import src.backend.academy.dto.AcademyUpdateRequest;
import src.backend.academy.query.AdminAcademyQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.authz.CanManageAcademy;

/**
 * 메인 관리자 콘솔의 학원 API(ACAD-01~04 · O-01, API_SPEC §6.1~§6.3).
 *
 * <p>전 학원 범위이며 학원 격리의 예외다(§1.5) — 대상 학원은 토큰이 아니라 경로 파라미터가 정한다.
 * 그래서 이 컨트롤러에는 {@code AuthUser} 를 받는 자리가 부재하다. 예외를 여는 판정은
 * {@link CanManageAcademy} 한 곳이고, 그 권한은 메인 관리자만 보유한다.
 */
@RestController
@RequestMapping("/admin/academies")
@RequiredArgsConstructor
public class AdminAcademyController {

    private final AcademyCommandService academyCommandService;

    private final AdminAcademyQueryService adminAcademyQueryService;

    /** 학원 목록·검색(ACAD-01, §6.1). */
    @CanManageAcademy
    @GetMapping
    public ApiResponse<PageResponse<AcademySummaryResponse>> list(@ModelAttribute AcademyListRequest request) {
        return ApiResponse.ok(adminAcademyQueryService.list(request));
    }

    /** 학원 등록(ACAD-02, §6.2) — 학원 코드는 서버가 만들어 응답에 싣는다. */
    @CanManageAcademy
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AcademyRegisterResponse> register(@Valid @RequestBody AcademyRegisterRequest request) {
        return ApiResponse.ok(academyCommandService.register(request));
    }

    /** 학원 상세(ACAD-03, §6.3). */
    @CanManageAcademy
    @GetMapping("/{id}")
    public ApiResponse<AcademyDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(adminAcademyQueryService.detail(id));
    }

    /**
     * 학원 정보 수정·비활성화(ACAD-04, §6.3).
     *
     * <p>수정과 조회를 나눠 부르는 이유는 §1.9 가 "변경 후 자원 상태를 그대로 반환" 을 요구하는데,
     * 그 상태에는 쓰기가 만들지 않는 값(관계자 수·소속 사용자 수)이 함께 들어가기 때문이다 —
     * 쓰기 쪽이 그것까지 조립하면 같은 집계가 두 곳에 생긴다.
     */
    @CanManageAcademy
    @PatchMapping("/{id}")
    public ApiResponse<AcademyDetailResponse> update(@PathVariable Long id,
            @Valid @RequestBody AcademyUpdateRequest request) {
        return ApiResponse.ok(adminAcademyQueryService.detail(academyCommandService.update(id, request)));
    }
}
