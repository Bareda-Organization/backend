package src.backend.schedule.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageSchedule;
import src.backend.schedule.command.ScheduleCommandService;
import src.backend.schedule.dto.ScheduleListRequest;
import src.backend.schedule.dto.ScheduleRegisterRequest;
import src.backend.schedule.dto.ScheduleResponse;
import src.backend.schedule.dto.ScheduleUpdateRequest;
import src.backend.schedule.query.ScheduleQueryService;

/**
 * 관계자 웹의 운행 스케줄 관리 API(SCH-01 · A-09, API_SPEC §5.10).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 경로·본문에 학원을 지정할 자리가 부재하고, 다른 학원의
 * 스케줄을 {@code {id}} 로 지목하면 {@code 404 SCHEDULE_NOT_FOUND} 다.
 *
 * <p>회차(SCH-02·03)는 여기 없다 — 대상이 계획이 아니라 <b>그날의 운행</b>이고 경로도
 * {@code /staff/runs} 다.
 */
@RestController
@RequestMapping("/staff/schedules")
@RequiredArgsConstructor
public class StaffScheduleController {

    private final ScheduleQueryService scheduleQueryService;

    private final ScheduleCommandService scheduleCommandService;

    /** 스케줄 목록(SCH-01, §5.10) — 비활성 스케줄도 실린다. */
    @CanManageSchedule
    @GetMapping
    public ApiResponse<PageResponse<ScheduleResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @ModelAttribute ScheduleListRequest request) {
        return ApiResponse.ok(scheduleQueryService.list(requester, request));
    }

    /** 스케줄 등록(SCH-01, §5.10) — 같은 유일성 조합이 이미 있으면 {@code 409 DUPLICATE_SCHEDULE}. */
    @CanManageSchedule
    @PostMapping
    public ApiResponse<ScheduleResponse> register(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody ScheduleRegisterRequest request) {
        return ApiResponse.ok(scheduleCommandService.register(requester, request));
    }

    /** 스케줄 수정(SCH-01, §5.10) — §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @CanManageSchedule
    @PatchMapping("/{id}")
    public ApiResponse<ScheduleResponse> update(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody ScheduleUpdateRequest request) {
        return ApiResponse.ok(scheduleCommandService.update(requester, id, request));
    }

    /** 스케줄 삭제(SCH-01, §5.10) — 행을 지우고, 이미 만들어진 회차는 남는다. */
    @CanManageSchedule
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id) {
        scheduleCommandService.delete(requester, id);
        return ApiResponse.ok(null);
    }
}
