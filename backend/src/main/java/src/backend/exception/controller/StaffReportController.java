package src.backend.exception.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.exception.dto.StaffReportItemResponse;
import src.backend.exception.dto.StaffReportListResponse;
import src.backend.exception.query.ExceptionReportQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadReport;

/**
 * 관계자 웹의 예외 보고 조회 API(API_SPEC §5.20).
 *
 * <p>{@code type}·{@code date}·{@code run_id} 를 {@code @RequestParam} 에 손으로 적는다 — 쿼리
 * 파라미터는 요청 본문과 달리 Jackson {@code SNAKE_CASE} 전략을 거치지 않는다({@link
 * src.backend.run.controller.StaffRunController} 가 이미 겪은 것과 같은 근거, DTO 로 묶어
 * {@code @ModelAttribute} 로 받으면 {@code run_id} 가 조용히 안 붙는다).
 */
@RestController
@RequestMapping("/staff/reports")
@RequiredArgsConstructor
public class StaffReportController {

    private final ExceptionReportQueryService exceptionReportQueryService;

    /** 예외 보고 목록(§5.20 목록) — 전부 선택적 필터, 페이지네이션 없음. */
    @CanReadReport
    @GetMapping
    public ApiResponse<StaffReportListResponse> list(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "run_id", required = false) Long runId) {
        return ApiResponse.ok(exceptionReportQueryService.list(requester, type, date, runId));
    }

    /** 예외 보고 상세(§5.20 상세). */
    @CanReadReport
    @GetMapping("/{id}")
    public ApiResponse<StaffReportItemResponse> detail(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id) {
        return ApiResponse.ok(exceptionReportQueryService.detail(requester, id));
    }
}
