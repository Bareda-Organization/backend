package src.backend.run.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageSchedule;
import src.backend.run.command.RunCommandService;
import src.backend.run.dto.RunCreateRequest;
import src.backend.run.dto.RunResponse;
import src.backend.run.query.RunQueryService;

/**
 * 관계자 웹의 일일 회차 조회·임시 조정 API(SCH-02·03 · A-09, API_SPEC §5.10).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 다른 학원의 회차를 {@code {id}} 로 지목하면
 * {@code 404 RUN_NOT_FOUND} 다.
 *
 * <p><b>회차를 만드는 정규 경로는 여기 없다</b> — 일일 생성은 요청이 아니라 날짜가 일으키는 배치이고
 * ({@code DailyRunGenerator}), 여기 있는 {@code POST} 는 스케줄에 없는 1회성 운행을 넣는 자리다.
 *
 * <p>배치(MGR-05·06)는 {@code StaffRunAssignmentController} 다 — 경로는 이 아래지만 권한이
 * {@code MANAGER_MANAGE} 로 다르고, 바뀌는 계기도 운행 계획이 아니라 인력 운영이다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/runs")
@RequiredArgsConstructor
public class StaffRunController {

    private final RunQueryService runQueryService;

    private final RunCommandService runCommandService;

    /**
     * 그 날짜의 회차 목록(SCH-02, §5.10) — 날짜를 주지 않으면 오늘이고, 취소된 회차도 실린다.
     *
     * <p>파라미터 이름을 {@code @RequestParam} 에 <b>손으로 적는다.</b> 쿼리 파라미터는 요청 본문과
     * 달리 Jackson 을 거치지 않아 {@code SNAKE_CASE} 전략(Ruling 104)이 적용되지 않는다 — DTO 로 묶어
     * {@code @ModelAttribute} 로 받으면 {@code service_date} 가 조용히 안 붙고 <b>목록이 늘 오늘</b>이
     * 된다. 실제로 그 형태로 깨졌다. 파라미터가 늘어 DTO 로 묶게 되면 이름 규약을 함께 정해야 한다.
     */
    @CanManageSchedule
    @Operation(summary = "운행 스케줄 · 일일 회차 — 그 날짜의 회차 목록")
    @GetMapping
    public ApiResponse<List<RunResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(name = "service_date", required = false) String serviceDate) {
        return ApiResponse.ok(runQueryService.list(requester, serviceDate));
    }

    /** 특정일 회차 임시 추가(SCH-03, §5.10) — 만들어진 회차는 {@code schedule_id} 가 비어 있다. */
    @CanManageSchedule
    @Operation(summary = "운행 스케줄 · 일일 회차 — 특정일 회차 임시 추가")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RunResponse> add(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody RunCreateRequest request) {
        return ApiResponse.ok(runCommandService.add(requester, request));
    }

    /** 특정일 회차 임시 취소(SCH-03, §5.10) — 행을 지우지 않고 {@code canceled_at} 을 채운다. */
    @CanManageSchedule
    @Operation(summary = "운행 스케줄 · 일일 회차 — 특정일 회차 임시 취소")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id) {
        runCommandService.cancel(requester, id);
        return ApiResponse.ok(null);
    }
}
