package src.backend.schedule.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageScheduleRequests;
import src.backend.global.security.authz.CanReadOwnChildren;
import src.backend.global.security.authz.CanSubmitGuardianRequest;
import src.backend.schedule.command.LocationChangeCommandService;
import src.backend.schedule.dto.CreateLocationChangeRequest;
import src.backend.schedule.dto.LocationChangeRequestResponse;
import src.backend.schedule.query.LocationChangeQueryService;

/**
 * 등하원 위치 변경 요청 API(P2). 신청은 학부모만 — 승인 단계가 없고 접수 즉시 자동 판정된다(D-H).
 * 관리자에게는 승인 권한이 아니라 <b>감사용 이력 조회</b>만 열려 있다.
 */
@Tag(name = "15. 위치변경(LocationChange)",
        description = "학부모의 등하원 **위치**(승차지/하차지) 변경 신청. ⚠️ 시간 변경(`12. 일정변경`)과 다른 API 다. "
                + "관리자 승인 단계가 없고 접수 즉시 노선 시뮬레이션으로 자동 판정한다 — 관리자에게는 감사용 조회만 열려 있다.")
@RestController
@RequestMapping("/api/location-change-requests")
public class LocationChangeController {

    private final LocationChangeCommandService locationChangeCommandService;
    private final LocationChangeQueryService locationChangeQueryService;

    public LocationChangeController(LocationChangeCommandService locationChangeCommandService,
                                    LocationChangeQueryService locationChangeQueryService) {
        this.locationChangeCommandService = locationChangeCommandService;
        this.locationChangeQueryService = locationChangeQueryService;
    }

    /** 학부모: 자녀 등하원 위치 변경 신청(자동 판정). */
    @PostMapping
    @CanSubmitGuardianRequest
    @Operation(summary = "등하원 위치 변경 신청 (학부모, 자동 판정)",
            description = """
                    자녀의 승차지(PICKUP)·하차지(DROPOFF) 좌표를 바꿔 달라고 신청한다. **관리자 승인 단계가 없다** —
                    접수 즉시 노선을 다시 계산해 서버가 스스로 판정하고, 결과를 `decision` 으로 돌려준다.

                    | decision | 언제 | 좌표가 바뀌나 |
                    |---|---|---|
                    | `BLOCKED` | 그 날짜·방향에 **운행 세션이 이미 존재**(시작·종료 무관) | 아니오 |
                    | `APPLIED` | 배차가 없거나 그 날짜의 노선 계획이 아직 없음 | 예 (좌표만 갱신, 이후 관리자가 배차) |
                    | `REPLANNED` | 계획이 있고 델타가 임계 이내 | 예 (+ 노선 version+1 재계산·재배포) |
                    | `REJECTED` | 델타가 임계 초과이거나 변경 후 정차 인원이 정원 초과 | 아니오 |

                    **자동 적용 임계는 AND 조건이다** — 소요시간 증가 **300초 이내 그리고** 총거리 증가 **1000m 이내**.
                    둘 중 하나만 넘어도 반려한다(OR 로 두면 "1km인데 20분 늘어난 경로"가 통과한다). 델타가 음수(단축)면 항상 통과다.

                    ⚠️ 어느 판정으로 끝나든 **신청 행은 항상 저장된다**(반려·차단도 감사 기록으로 남긴다). 그래서 `REJECTED` 도 HTTP 200 이다 —
                    "요청 처리 실패"가 아니라 "판정 결과가 반려"라는 뜻이다.

                    ⚠️ 하차지를 바꿔도 **`Stop` 행은 절대 건드리지 않는다** — 정류장은 여러 학생이 공유하는 행이라
                    한 명의 변경이 같은 정류장의 다른 학생까지 끌고 가면 안 된다. 학생 자신의 좌표 컬럼만 갱신한다.

                    `busId` 를 받지 않는다 — 대상 버스는 학생의 배정 버스에서 유도하며, 자기 자녀가 아니면 403 이다.
                    시드 기준 `parent@school.com` 토큰으로 예시값(학생 1 = 김민준)을 그대로 보내면 성공한다.
                    """,
            tags = {"00. MVP 사용 API", "15. 위치변경(LocationChange)"})
    public ApiResponse<LocationChangeRequestResponse> create(@AuthenticationPrincipal AuthUser parent,
                                                            @Valid @RequestBody CreateLocationChangeRequest request) {
        return ApiResponse.ok(locationChangeCommandService.create(parent, request));
    }

    /** 학부모: 내 신청 이력. */
    @GetMapping("/children")
    @CanReadOwnChildren
    @Operation(summary = "내 신청 이력 (학부모)",
            description = "연결된 자녀 전원의 신청 이력. 반려(`REJECTED`)·차단(`BLOCKED`) 건도 판정 사유(`reason`)와 함께 나온다 — "
                    + "학부모가 '왜 안 됐는지'를 앱에서 그대로 읽게 하기 위해서다. 파라미터가 없어 남의 자녀 이력을 볼 수 없다.",
            tags = {"00. MVP 사용 API", "15. 위치변경(LocationChange)"})
    public ApiResponse<List<LocationChangeRequestResponse>> children(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(locationChangeQueryService.getChildrenRequests(parent));
    }

    /** 관리자: 학원 신청 이력(반려·차단 포함 — 감사 목적). */
    @GetMapping
    @CanManageScheduleRequests
    @Operation(summary = "학원 신청 이력 (관리자, 감사용)",
            description = "⚠️ **승인·반려 권한이 아니다** — 판정은 이미 서버가 끝냈고 관리자는 결과를 열람만 한다. "
                    + "이 저장소에는 별도 감사 로그 테이블이 없어서, 이 목록이 '누가 무엇을 시도했는지'의 유일한 기록이다. "
                    + "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.",
            tags = {"00. MVP 사용 API", "15. 위치변경(LocationChange)"})
    public ApiResponse<List<LocationChangeRequestResponse>> tenant(@AuthenticationPrincipal AuthUser admin,
                                                                   @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(locationChangeQueryService.getTenantRequests(admin, tenantId));
    }
}
