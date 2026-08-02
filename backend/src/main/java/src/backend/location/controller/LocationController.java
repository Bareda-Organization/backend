package src.backend.location.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanMonitorOperations;
import src.backend.global.security.authz.CanOperateDrive;
import src.backend.global.security.authz.CanReadOwnChildren;
import src.backend.global.security.authz.CanReadOwnRecords;
import src.backend.global.security.authz.CanReportOwnLocation;
import src.backend.location.command.BusLocationCommandService;
import src.backend.location.command.LocationCommandService;
import src.backend.location.dto.BusLocationReportRequest;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.dto.LocationView;
import src.backend.location.query.BusLocationQueryService;
import src.backend.location.query.LocationQueryService;

/**
 * 실시간 위치 API. 학생 앱은 자기 좌표를 보고하고(POST), 나머지 계층은 각자 권한 범위에서 조회한다.
 * 역할 제한은 권한 애너테이션으로, 세밀한 학원·담당 격리는 서비스 계층에서 추가 검사한다(승하차 기록과 동일).
 */
@Tag(name = "08. 위치(Location)", description = "학생·버스 실시간 위치 보고·조회(F1). 보고는 각 역할 본인, 조회는 역할별 권한 범위에서.")
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationCommandService locationCommandService;
    private final LocationQueryService locationQueryService;
    private final BusLocationCommandService busLocationCommandService;
    private final BusLocationQueryService busLocationQueryService;

    public LocationController(LocationCommandService locationCommandService,
                              LocationQueryService locationQueryService,
                              BusLocationCommandService busLocationCommandService,
                              BusLocationQueryService busLocationQueryService) {
        this.locationCommandService = locationCommandService;
        this.locationQueryService = locationQueryService;
        this.busLocationCommandService = busLocationCommandService;
        this.busLocationQueryService = busLocationQueryService;
    }

    /** 학생: 자기 현재 위치 보고(실 GPS 전환 대비 엔드포인트, MVP 는 Mock 이 대체). */
    @Operation(summary = "학생 본인 위치 보고 (학생) — 실사용 없음",
            description = "⚠️ **학생 앱이 없어 실제로 이 API 를 부르는 클라이언트가 없다.** 실 GPS 전환 대비로 계약만 열어 둔 경로다. "
                    + "관제·학부모 지도가 쓰는 것은 기사가 보고하는 `POST /api/locations/bus` 쪽이다.")
    @PostMapping
    @CanReportOwnLocation
    public ApiResponse<Void> report(@AuthenticationPrincipal AuthUser student,
                                    @Valid @RequestBody LocationReportRequest request) {
        locationCommandService.reportSelf(student, request);
        return ApiResponse.ok(null);
    }

    /** 학생: 본인 최신 위치. */
    @Operation(summary = "내 최신 위치 (학생) — 보고한 적 없으면 빈 값",
            description = "`POST /api/locations` 로 먼저 보고해야 값이 나온다. 학생 앱이 없어 시드 상태에서는 비어 있다.")
    @GetMapping("/me")
    @CanReadOwnRecords
    public ApiResponse<LocationView> myLocation(@AuthenticationPrincipal AuthUser student) {
        return ApiResponse.ok(locationQueryService.getMyLocation(student));
    }

    /** 학부모: 자녀(형제자매 포함) 최신 위치 목록. */
    @Operation(summary = "자녀 본인 좌표 (학부모) — 현재 항상 빈 응답",
            description = "⚠️ **학생 앱이 없어 학생 좌표를 보고하는 주체가 없다** — 그래서 이 API 는 사실상 항상 빈 배열이다. "
                    + "학부모 앱이 실제로 쓰는 것은 `GET /api/locations/children/buses`(자녀가 탄 버스 위치) 쪽이다. "
                    + "실 GPS 전환 대비로 계약만 열어 둔 경로다.")
    @GetMapping("/children")
    @CanReadOwnChildren
    public ApiResponse<List<LocationView>> childrenLocations(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(locationQueryService.getChildrenLocations(parent));
    }

    /** 기사: 담당 버스 탑승 학생들의 최신 위치 목록. */
    @Operation(summary = "담당 버스 학생들의 위치 (기사) — 현재 항상 빈 응답",
            description = "⚠️ 학생 좌표를 보내는 주체(학생 앱)가 없어 실질적으로 항상 빈 배열이다. "
                    + "기사 앱이 실제로 쓰는 것은 명단(`/api/drive-sessions/{id}/roster`)의 승하차지 좌표다. 본인 담당 버스가 아니면 거부된다.")
    @GetMapping("/bus/{busId}")
    @CanOperateDrive
    public ApiResponse<List<LocationView>> busLocations(@AuthenticationPrincipal AuthUser driver,
                                                        @Parameter(example = "1") @PathVariable Long busId) {
        return ApiResponse.ok(locationQueryService.getBusLocations(driver, busId));
    }

    /** 관리자: 학원 학생들의 최신 위치 목록(관제). */
    @Operation(summary = "학원 학생들의 위치 (관리자) — 현재 항상 빈 응답",
            description = "⚠️ 위와 같은 이유로 빈 배열이다. 관제 지도가 쓰는 것은 **버스** 위치인 `GET /api/locations/buses` 다 — 혼동하기 쉬운 지점이다.")
    @GetMapping
    @CanMonitorOperations
    public ApiResponse<List<LocationView>> tenantLocations(@AuthenticationPrincipal AuthUser admin,
                                                           @Parameter(example = "1") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(locationQueryService.getTenantLocations(admin, tenantId));
    }

    /** 기사: 담당 버스의 현재 위치 보고(실 GPS 전환 대비 엔드포인트, MVP는 Mock이 대체, F1). */
    @PostMapping("/bus")
    @CanOperateDrive
    @Operation(summary = "버스 위치 보고 (기사 전용)",
            description = "⚠️ 승하차 기록은 선탑자로 넘어갔지만 **버스 위치 보고는 기사가 그대로 유지**한다 — 선탑자 토큰이면 403 이다. "
                    + "본인 담당 버스만 보고할 수 있다. 저장과 동시에 관리자(`/topic/tenant/{tenantId}/bus-locations`)와 "
                    + "학부모(`/user/queue/bus-location`) STOMP 구독자에게 즉시 push 된다.",
            tags = {"00. MVP 사용 API", "08. 위치(Location)"})
    public ApiResponse<Void> reportBusLocation(@AuthenticationPrincipal AuthUser driver,
                                               @Valid @RequestBody BusLocationReportRequest request) {
        busLocationCommandService.reportSelf(driver, request);
        return ApiResponse.ok(null);
    }

    /** 관리자: 학원 버스들의 최신 위치 목록(관제 지도 표시용, F1). */
    @GetMapping("/buses")
    @CanMonitorOperations
    @Operation(summary = "학원 버스 실시간 위치 (관리자 관제)",
            description = "관제 지도가 처음 그릴 때 쓰는 스냅샷이다. 이후 갱신은 STOMP "
                    + "`/topic/tenant/{tenantId}/bus-locations` 구독으로 받는다(폴링하지 않는다). "
                    + "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "08. 위치(Location)"})
    public ApiResponse<List<BusLocationView>> tenantBusLocations(
            @AuthenticationPrincipal AuthUser admin,
            @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(busLocationQueryService.getTenantBusLocations(admin, tenantId));
    }

    /** 학부모: 자녀가 탄 버스들의 최신 위치(P1). busId 를 입력받지 않아 타 버스 노출이 구조적으로 불가능하다. */
    @GetMapping("/children/buses")
    @CanReadOwnChildren
    @Operation(summary = "자녀가 탄 버스의 실시간 위치 (학부모)",
            description = "학부모 앱 지도의 스냅샷 조회다(이후 갱신은 STOMP `/user/queue/bus-location`). "
                    + "**파라미터가 없다** — busId 를 받지 않으므로 남의 버스를 찍어 볼 방법이 구조적으로 없다. "
                    + "연결된 자녀들의 배정 버스를 서버가 역으로 찾아 중복 제거해 돌려준다. "
                    + "⚠️ 자녀 본인의 좌표가 아니라 **버스 좌표**다 — 학생 앱이 없어 학생 좌표를 보내는 주체가 없기 때문이다"
                    + "(그래서 `GET /api/locations/children` 은 항상 빈 응답이다). "
                    + "시드에 버스 위치 보고 이력이 없으면 빈 배열이 나온다 — 기사 토큰으로 `POST /api/locations/bus` 를 한 번 먼저 부른다.",
            tags = {"00. MVP 사용 API", "08. 위치(Location)"})
    public ApiResponse<List<BusLocationView>> childrenBusLocations(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(busLocationQueryService.getChildrenBusLocations(parent));
    }
}
