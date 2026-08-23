package src.backend.operations.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.operations.dto.BusOperationDetail;
import src.backend.operations.dto.BusOperationSummary;
import src.backend.operations.query.OperationsQueryService;

/**
 * 관리자 관제 목록(BG-21) 전용 컨트롤러. 지표 카드({@code OperationsSummaryController})와
 * 별개 파일이다 — 요약은 학원 전체를 숫자 몇 개로 접지만, 이건 버스마다 한 줄이라 화면 갱신 주기·
 * 응답 크기가 달라 분리해 각 컨트롤러의 책임을 좁게 유지한다.
 */
@Tag(name = "16. 운영현황(Operations)", description = "관리자 첫 화면 지표 카드 — 오늘 운행 현황·대기 처리·배차 이상·컴플라이언스·알림·출결·이상 징후.")
@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final OperationsQueryService operationsQueryService;

    public OperationsController(OperationsQueryService operationsQueryService) {
        this.operationsQueryService = operationsQueryService;
    }

    @GetMapping("/buses")
    @CanMonitorOperations
    @Operation(summary = "버스별 운행 현황 목록 (관리자)",
            description = "학원의 버스마다 한 줄 — 진행 상태·실시간 좌표·탑승 5종 집계(대기/탑승/하차/결석/미탑승)·"
                    + "배치 인력을 담는다. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다. "
                    + "⚠️ `counts.absent`(승인된 결석)와 `counts.noShow`(연락 없이 안 옴)는 서로 다른 값이다 — "
                    + "결석 신고가 이미 승인된 학생은 미탑승이 아니라 결석으로 잡혀 불필요한 전화를 걸지 않는다. "
                    + "⚠️ `crew.attendantMissing` 이 true 면 이 버스는 승하차를 기록할 사람이 없다는 뜻이다(배차 필수). "
                    + "⚠️ `location.stale` 이 true 면 좌표가 15초 넘게 갱신되지 않은 것이라 화면은 최근 위치로 표시하면 안 된다.",
            tags = {"16. 운영현황(Operations)"})
    public ApiResponse<List<BusOperationSummary>> buses(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수")
            @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(operationsQueryService.getBusOperations(admin, tenantId));
    }

    @GetMapping("/buses/{busId}")
    @CanMonitorOperations
    @Operation(summary = "버스 1대 상세 (관리자)",
            description = "선택한 버스의 학생별 상태 + 경로 — 목록 화면에서 버스 하나를 골랐을 때의 상세 화면용. "
                    + "`students[].status` 는 목록과 같은 5종(waiting/boarded/alighted/absent/no_show)이고, "
                    + "`students[].guardians[]` 로 보호자 연락처를 함께 내려 미탑승 발견 시 바로 연락할 수 있게 한다. "
                    + "⚠️ `routePlan` 은 당일 배포된 계획이 없으면 null 이다(버스·학생 명단은 계획과 무관하게 유효해 404 로 다루지 않는다). "
                    + "⚠️ `routePlan.stops[].reachedAtEstimate` 와 `session.startedAt` 기반의 정차 도달 시각은 전부 "
                    + "세션 시작 시각 + 계획 ETA 로 계산한 추정치다 — 실제 도달 시각을 기록하는 수단이 없다.",
            tags = {"16. 운영현황(Operations)"})
    public ApiResponse<BusOperationDetail> busDetail(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "버스 id") @PathVariable Long busId) {
        return ApiResponse.ok(operationsQueryService.getBusOperationDetail(admin, busId));
    }
}
