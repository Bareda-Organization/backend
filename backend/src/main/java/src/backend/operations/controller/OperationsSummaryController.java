package src.backend.operations.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.operations.dto.OperationsSummary;
import src.backend.operations.query.OperationsSummaryQueryService;

/**
 * 관리자 첫 화면 지표 카드(BG-22) 전용 컨트롤러. 관제 화면(버스 목록·상세, {@code OperationsController})과
 * 별개 파일이다 — 요약 하나가 도메인 여러 개를 조합하므로 분리해 각 컨트롤러의 책임을 좁게 유지한다.
 */
@Tag(name = "16. 운영현황(Operations)", description = "관리자 첫 화면 지표 카드 — 오늘 운행 현황·대기 처리·배차 이상·컴플라이언스·알림·출결·이상 징후.")
@RestController
@RequestMapping("/api/operations")
public class OperationsSummaryController {

    private final OperationsSummaryQueryService operationsSummaryQueryService;

    public OperationsSummaryController(OperationsSummaryQueryService operationsSummaryQueryService) {
        this.operationsSummaryQueryService = operationsSummaryQueryService;
    }

    @GetMapping("/summary")
    @CanMonitorOperations
    @Operation(summary = "운영 현황 요약 (관리자)",
            description = "오늘 운행 현황·대기 처리 건수·배차 이상·보험 컴플라이언스·당일 알림 이력·회차별 탑승 집계·"
                    + "이상 징후를 한 번에 모은다. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다. "
                    + "⚠️ `anomalies.delayedBuses` 는 계획 ETA 기반 추정치다(실제 정류장 도달 기록 없음).",
            tags = {"16. 운영현황(Operations)"})
    public ApiResponse<OperationsSummary> summary(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수")
            @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(operationsSummaryQueryService.getSummary(admin, tenantId));
    }
}
