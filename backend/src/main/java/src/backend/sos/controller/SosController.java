package src.backend.sos.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import src.backend.sos.command.SosCommandService;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;
import src.backend.sos.query.SosQueryService;

/**
 * SOS API. 발신은 학생만, 확인·종료는 관리자만 — 조회는 4계층이 각자 범위에서.
 */
@Tag(name = "13. SOS", description = "긴급 SOS 발신·확인·해제, 자동 에스컬레이션. 발신은 학생, 확인·종료는 관리자.")
@RestController
@RequestMapping("/api/sos-events")
public class SosController {

    private final SosCommandService sosCommandService;
    private final SosQueryService sosQueryService;

    public SosController(SosCommandService sosCommandService, SosQueryService sosQueryService) {
        this.sosCommandService = sosCommandService;
        this.sosQueryService = sosQueryService;
    }

    /** 학생: SOS 발신. */
    @Operation(summary = "SOS 발신 (학생 전용)",
            description = "`student@school.com` 토큰이 필요하다. 좌표를 생략하면 마지막으로 보고된 위치가 쓰인다. "
                    + "발신 즉시 학원 관리자에게 알림이 나가고, 확인이 늦으면 자동 에스컬레이션된다.")
    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<SosEventResponse> trigger(@AuthenticationPrincipal AuthUser student,
                                                 @Valid @RequestBody SosTriggerRequest request) {
        return ApiResponse.ok(sosCommandService.trigger(student, request));
    }

    /** 관리자: 확인(OPEN → ACKNOWLEDGED). */
    @Operation(summary = "SOS 확인 (OPEN → ACKNOWLEDGED)",
            description = "'봤다'는 표시일 뿐 종료가 아니다. 종료는 `/resolve` 다. `{id}` 는 학생 토큰으로 POST 를 먼저 불러 얻는다.")
    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<SosEventResponse> acknowledge(@AuthenticationPrincipal AuthUser admin,
                                                      @Parameter(example = "1", description = "SOS 이벤트 id — 시드에 없으므로 학생 토큰으로 POST 를 먼저 부른다") @PathVariable Long id) {
        return ApiResponse.ok(sosCommandService.acknowledge(admin, id));
    }

    /** 관리자: 종료(ACKNOWLEDGED → RESOLVED). */
    @Operation(summary = "SOS 종료 (ACKNOWLEDGED → RESOLVED)",
            description = "⚠️ `OPEN` 에서 바로 종료할 수 없다 — 먼저 `/acknowledge` 를 거쳐야 한다. "
                    + "'누가 언제 확인했는지'가 빠진 종료를 막기 위한 순서다.")
    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<SosEventResponse> resolve(@AuthenticationPrincipal AuthUser admin,
                                                 @Parameter(example = "1", description = "SOS 이벤트 id — acknowledge 를 거친 뒤에만 종료할 수 있다") @PathVariable Long id) {
        return ApiResponse.ok(sosCommandService.resolve(admin, id));
    }

    /** 학생: 본인 SOS 이력. */
    @Operation(summary = "내 SOS 이력 (학생)", description = "파라미터가 없어 남의 이력을 볼 수 없다.")
    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<SosEventResponse>> myEvents(@AuthenticationPrincipal AuthUser student) {
        return ApiResponse.ok(sosQueryService.getMyEvents(student));
    }

    /** 학부모: 자녀 SOS 이력. */
    @Operation(summary = "자녀 SOS 이력 (학부모)", description = "연결된 자녀 전원(형제자매 포함)의 이력.")
    @GetMapping("/children")
    @PreAuthorize("hasRole('PARENT')")
    public ApiResponse<List<SosEventResponse>> childrenEvents(@AuthenticationPrincipal AuthUser parent) {
        return ApiResponse.ok(sosQueryService.getChildrenEvents(parent));
    }

    /** 관리자: 학원 SOS 이력. */
    @Operation(summary = "학원 SOS 이력 (관리자)",
            description = "`tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")
    public ApiResponse<List<SosEventResponse>> tenantEvents(@AuthenticationPrincipal AuthUser admin,
                                                            @Parameter(example = "1", description = "학원 id. 학원 관리자는 생략 가능, 플랫폼 관리자는 필수") @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(sosQueryService.getTenantEvents(admin, tenantId));
    }
}
