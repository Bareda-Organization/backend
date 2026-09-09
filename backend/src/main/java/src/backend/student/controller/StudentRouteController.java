package src.backend.student.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStudentRoute;
import src.backend.student.dto.StudentRouteResponse;
import src.backend.student.query.StudentRouteQueryService;

/**
 * 학부모 앱의 자녀 노선 조회 API(LOC-03, API_SPEC §3.10, 목표 12).
 *
 * <p>{@code date}·{@code run_id} 둘 다 선택이다(§3.10). {@code run_id} 는 명세상 문자열 타입으로
 * 문서화돼 있어 — 내부적으로는 {@code Long} 이지만 — {@code String} 그대로 받아
 * {@code StudentRouteQueryService} 에서 파싱한다(형변환 실패를 {@code 422} 로 응답하는 지점을
 * 서비스 계층에 모아 두기 위함 — {@code NavigationController} 의 {@code scope} 처리와 같은 방식).
 *
 * <p>§3.10 이 문서화한 권한은 "학부모 · 학생" 이지만, 이 코드베이스에는 학생 본인 접근 경로
 * ({@code Role.STUDENT} 자기 조회) 가 존재하지 않는다(전수 확인) — 표 누락으로 보고한다.
 */
@RestController
@RequestMapping("/students/{id}/route")
@RequiredArgsConstructor
public class StudentRouteController {

    private final StudentRouteQueryService studentRouteQueryService;

    @CanReadStudentRoute
    @GetMapping
    public ApiResponse<StudentRouteResponse> route(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId, @RequestParam(required = false) String date,
            @RequestParam(name = "run_id", required = false) String runId) {
        return ApiResponse.ok(studentRouteQueryService.route(authUser, studentId, date, runId));
    }
}
