package src.backend.routing.controller;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
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
import src.backend.routing.command.RoutingCommandService;
import src.backend.routing.dto.AutoAssignRequest;
import src.backend.routing.dto.AutoAssignResponse;
import src.backend.routing.dto.ConfirmAutoAssignRequest;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.query.RoutingQueryService;

/**
 * 노선 계획(RoutePlan) API. 생성·승인·배포는 관리자, 배포 완료 계획 조회는 담당 기사 —
 * 역할별로 다른 엔드포인트라 클래스 레벨 대신 메서드마다 {@code @PreAuthorize}를 둔다(rideevent와 동일 스타일).
 */
@Tag(name = "07. 배차·노선계획(Routing)", description = "노선 계획 생성, 멀티버스 자동배정(F4)/확정, 승인·배포, 기사 조회.")
@RestController
@RequestMapping("/api/route-plans")
public class RoutingController {

    private final RoutingCommandService routingCommandService;
    private final RoutingQueryService routingQueryService;
    private final RoutePlanSimulationService routePlanSimulationService;

    public RoutingController(RoutingCommandService routingCommandService,
                             RoutingQueryService routingQueryService,
                             RoutePlanSimulationService routePlanSimulationService) {
        this.routingCommandService = routingCommandService;
        this.routingQueryService = routingQueryService;
        this.routePlanSimulationService = routePlanSimulationService;
    }

    /** 관리자: 자동배치 계획 생성 — sweep+NN+2-opt 로 순서 최적화 후 directions API 1회(또는 청킹) 호출. */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "단일 버스 노선 계획 생성 (DRAFT)",
            description = "이미 그 버스에 배정된 학생들의 방문 순서를 최적화해 계획을 만든다(sweep + nearest-neighbour + 2-opt). "
                    + "학생 배정 자체는 바꾸지 않는다 — 배정까지 다시 짜려면 `/auto-assign` 쪽을 쓴다. "
                    + "상태는 `DRAFT` 라서 `/{id}/approve` → `/{id}/publish` 를 거쳐야 기사·선탑자에게 보인다.")
    public ApiResponse<RoutePlanResponse> generate(@AuthenticationPrincipal AuthUser admin,
                                                    @Valid @RequestBody GenerateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.generate(admin, request));
    }

    /** 관리자: 멀티버스 자동 배정(제안) — Sweep 클러스터링으로 버스별 RECOMMENDED 계획을 만든다(배정 확정 전, F4). */
    @PostMapping("/auto-assign")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "멀티버스 자동 배정 제안 (미확정)",
            description = "학원의 전체 활성 학생을 Sweep 클러스터링으로 버스별로 나눠 `RECOMMENDED` 계획들을 만든다. "
                    + "⚠️ 이 단계에서는 **학생 배정이 아직 바뀌지 않는다** — 제안일 뿐이다. "
                    + "응답의 `plans[].id` 를 `/auto-assign/confirm` 에 넘겨야 실제 배정·배포가 일어난다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<AutoAssignResponse> autoAssign(@AuthenticationPrincipal AuthUser admin,
                                                       @Valid @RequestBody AutoAssignRequest request) {
        return ApiResponse.ok(routingCommandService.autoAssign(admin, request));
    }

    /** 관리자: 자동 배정 확정 — 학생 배정을 커밋하고 승인·배포까지 이어서 수행한다(F4). */
    @PostMapping("/auto-assign/confirm")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "자동 배정 확정 (배정 커밋 + 승인 + 배포)",
            description = "`/auto-assign` 응답의 `plans[].id` 목록을 넘기면 학생 배정을 실제로 커밋하고 승인·배포까지 이어서 한다. "
                    + "⚠️ `id` 목록을 직접 만들어 넣지 말고 **바로 앞 `/auto-assign` 응답의 값을 쓴다** — "
                    + "예시값 `[1, 2]` 는 계획 테이블이 비어 있는 초기 시드에서 생성되는 id 를 가정한 것이라, "
                    + "이미 계획을 만들어 본 DB 에서는 다른 번호가 된다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> confirmAutoAssign(@AuthenticationPrincipal AuthUser admin,
                                                                   @Valid @RequestBody ConfirmAutoAssignRequest request) {
        return ApiResponse.ok(routingCommandService.confirmAutoAssign(admin, request.planIds()));
    }

    /** 관리자: 배차 변경안 시뮬레이션 — 저장하지 않고 baseline vs candidate 를 비교한다(요구 8). */
    @PostMapping("/simulate")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "배차 변경안 비교 (미저장)",
            description = """
                    `overrides` 를 얹어 노선을 다시 계산하고 **baseline(현재 최신 계획) vs candidate(변경안)** 를 비교해 준다.

                    ⚠️ **어떤 행도 저장하지 않는다.** 학생 배정도, 노선 계획도 그대로다 — 저장은 `/simulate/apply` 에서만 일어난다.
                    화면에서 "여러 안을 눌러 보다가 하나를 고르는" 흐름을 위해 계산과 저장을 갈라 놓은 것이다.

                    - `baseline` — 그 버스·방향의 현재 최신 계획. **계획이 아직 없으면 `null` 이고 `delta` 도 `null`** 이다.
                    - `candidate.routePlanId` · `version` 은 저장하지 않았으므로 **항상 `null`** 이다.
                    - `delta` 는 언제나 `candidate − baseline` 이다(음수 = 줄어듦).
                    - `seatCapacity` 는 baseline 유무와 무관하게 항상 채워진다.

                    ⚠️ **정원 초과여도 예외로 죽이지 않는다** — `candidate.stops().size() > seatCapacity` 를 화면이 판단한다.
                    비교 화면이 정원 때문에 통째로 실패하면 관리자가 "왜 안 되는지"를 볼 수 없기 때문이다.
                    ("보여주기는 관대, 저장은 엄격" — `/simulate/apply` 는 정원 초과를 거부한다.)

                    좌표가 없는 학생은 조용히 제외된다(한 명 때문에 비교 전체가 실패하지 않게). 변경안 적용 후 정차가 0개면 400 이다.
                    """,
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanComparison> simulate(@AuthenticationPrincipal AuthUser admin,
                                                      @Valid @RequestBody SimulateRoutePlanRequest request) {
        return ApiResponse.ok(routePlanSimulationService.simulate(admin, request));
    }

    /** 관리자: 배차 변경안 채택 — 요청의 overrides 로 서버가 재계산해 배정 커밋 + version+1 계획 배포까지 수행한다. */
    @PostMapping("/simulate/apply")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "배차 변경안 채택 (저장·배포)",
            description = """
                    `/simulate` 로 본 안을 실제로 적용한다. **요청 본문은 `/simulate` 와 똑같다** — 비교 응답을 그대로 되돌려보내지 않고
                    서버가 `overrides` 로 **다시 계산한다**. 클라이언트가 계산 결과를 들고 오면 그 값을 위조해 노선을 심을 수 있기 때문이다.

                    한 번의 호출로 ① 학생 배정 커밋 → ② 노선 계획 생성(version + 1 인 **새 행**, 기존 행은 수정하지 않는다) →
                    ③ 승인 → ④ 배포까지 이어서 수행한다. 배포 즉시 기사·선탑자의 `GET /api/route-plans/driver/{busId}` 에 노출되고
                    노선 배포 알림이 나간다.

                    ⚠️ **여기서는 정원 초과를 거부한다(400).** 비교(`/simulate`)는 초과 상태도 보여주지만 저장은 `Bus.seatCapacity` 를 넘길 수 없다.
                    """,
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> applySimulation(@AuthenticationPrincipal AuthUser admin,
                                                           @Valid @RequestBody SimulateRoutePlanRequest request) {
        return ApiResponse.ok(routingCommandService.applySimulation(admin, request));
    }

    /** 관리자: 승인(DRAFT/RECOMMENDED → APPROVED). */
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "노선 계획 승인 (DRAFT/RECOMMENDED → APPROVED)",
            description = "`{id}` 는 `/generate` 또는 `/auto-assign` 응답의 계획 id 다. "
                    + "시드에는 노선 계획이 없으므로 먼저 하나 만들어야 한다. 승인만으로는 기사에게 보이지 않는다 — `/publish` 까지 해야 한다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> approve(@AuthenticationPrincipal AuthUser admin,
                                                   @Parameter(example = "1", description = "노선 계획 id") @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.approve(admin, id));
    }

    /** 관리자: 배포(APPROVED → PUBLISHED) — 배포 즉시 담당 기사 조회 API에 노출된다. */
    @PatchMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "노선 계획 배포 (APPROVED → PUBLISHED)",
            description = "배포하는 순간 기사·선탑자의 `GET /api/route-plans/driver/{busId}` 에 노출되고, 학부모에게 노선 배포 알림이 나간다. "
                    + "`APPROVED` 가 아닌 계획을 배포하려 하면 거부된다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> publish(@AuthenticationPrincipal AuthUser admin,
                                                   @Parameter(example = "1", description = "노선 계획 id") @PathVariable Long id) {
        return ApiResponse.ok(routingCommandService.publish(admin, id));
    }

    /** 관리자: 노선 계획 상세(정차 순서 포함). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "노선 계획 상세 (관리자)",
            description = "정차 순서·좌표·누적 ETA·polyline 을 포함한다. `{id}` 는 `/generate`·`/auto-assign`·`GET /api/route-plans` 에서 얻는다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<RoutePlanResponse> detail(@AuthenticationPrincipal AuthUser admin,
                                                  @Parameter(example = "1", description = "노선 계획 id") @PathVariable Long id) {
        return ApiResponse.ok(routingQueryService.get(admin, id));
    }

    /** 관리자: 학원(또는 특정 버스) 노선 계획 목록. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "노선 계획 목록 (관리자)",
            description = "학원 전체 또는 특정 버스의 계획 목록. 노선 변경은 언제나 version+1 인 **새 행**이라, 같은 버스·방향에 "
                    + "여러 버전이 쌓인 것이 정상이다. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> list(@AuthenticationPrincipal AuthUser admin,
                                                      @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId,
                                                      @Parameter(example = "1", description = "버스 id로 좁히기. 생략하면 학원 전체") @RequestParam(required = false) Long busId) {
        return ApiResponse.ok(routingQueryService.list(admin, tenantId, busId));
    }

    /** 기사·선탑자: 담당 버스의 당일(또는 지정일) 배포 완료 노선(등원/하원, pull 방식). */
    @GetMapping("/driver/{busId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ATTENDANT')")
    @Operation(summary = "담당 버스의 배포된 노선 (기사·선탑자)",
            description = "⚠️ **2026-08-02 확장으로 선탑자(ATTENDANT)에게도 열렸다**(기존에는 기사 전용). "
                    + "`PUBLISHED` 상태만 나온다 — 승인 전 초안은 담당자에게 보이지 않는다. `serviceDate` 를 비우면 오늘이다. "
                    + "본인 담당 버스가 아니면 거부된다. 기사 앱의 '운행 중일 때만 노선 경로 표시'가 이 응답을 그린다.",
            tags = {"00. MVP 사용 API", "07. 배차·노선계획(Routing)"})
    public ApiResponse<List<RoutePlanResponse>> driverPublished(
            @AuthenticationPrincipal AuthUser crew,
            @Parameter(example = "1", description = "버스 id(1=3호차)") @PathVariable Long busId,
            @Parameter(example = "2026-07-20", description = "운행일. 생략하면 오늘") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return ApiResponse.ok(routingQueryService.getPublishedForDriver(crew, busId, serviceDate));
    }
}
