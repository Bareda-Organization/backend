package src.backend.student.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStudentBusPosition;
import src.backend.student.dto.StudentBusPositionResponse;
import src.backend.student.query.StudentBusPositionQueryService;

/**
 * 학부모 앱의 실시간 버스 위치 API(LOC-02, API_SPEC §3.11, 목표 9·11).
 *
 * <p>쿼리 파라미터가 없다 — §3.11 은 항상 오늘 날짜, 그 학생의 오늘 회차를 답한다.
 *
 * <p>{@code @CanReadStudentBusPosition} 은 {@code isAuthenticated()} 만 검사한다 — §3.11 에는
 * {@code CanReadStudentRoute} 가 쓰는 {@code ROUTE_READ} 같은 전용 권한 카탈로그 항목이 부재하다
 * ({@code Permissions} 전수 확인). 연결되지 않은 자녀는 권한이 아니라
 * {@code student.access.LinkedChildLookup} 이 {@code 403} 으로 걸러낸다.
 */
@Tag(name = ApiTags.PARENT_STUDENT)
@RestController
@RequestMapping("/students/{id}/bus-position")
@RequiredArgsConstructor
public class StudentBusPositionController {

    private final StudentBusPositionQueryService studentBusPositionQueryService;

    @CanReadStudentBusPosition
    @Operation(summary = "실시간 버스 위치 (LOC-02, P-07 · S-02)")
    @GetMapping
    public ApiResponse<StudentBusPositionResponse> position(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId) {
        return ApiResponse.ok(studentBusPositionQueryService.position(authUser, studentId));
    }
}
