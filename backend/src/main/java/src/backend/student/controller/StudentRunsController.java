package src.backend.student.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStudentRuns;
import src.backend.student.dto.StudentRunsResponse;
import src.backend.student.query.StudentRunsQueryService;

/**
 * 자녀·본인 당일 회차 목록 API(P-04 · S-01, API_SPEC §3.5, 목표 5).
 *
 * <p>{@code date} 는 선택이며 생략하면 당일이다(§3.5) — 파싱·기본값 처리는
 * {@code StudentRunsQueryService} 에 모은다({@code StudentRouteController} 의 {@code run_id} 처리와
 * 같은 방식).
 */
@Tag(name = ApiTags.PARENT_STUDENT)
@RestController
@RequestMapping("/students/{id}/runs")
@RequiredArgsConstructor
public class StudentRunsController {

    private final StudentRunsQueryService studentRunsQueryService;

    @CanReadStudentRuns
    @Operation(summary = "자녀의 당일 회차 (P-04 · S-01)")
    @GetMapping
    public ApiResponse<StudentRunsResponse> runs(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId, @RequestParam(required = false) String date) {
        return ApiResponse.ok(studentRunsQueryService.runs(authUser, studentId, date));
    }
}
