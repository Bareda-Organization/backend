package src.backend.run.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadRoster;
import src.backend.run.dto.ManagerRunResponse;
import src.backend.run.query.ManagerRunQueryService;

/**
 * 매니저 앱의 담당 회차 목록(API_SPEC §4.1 {@code GET /manager/runs}, RUN-01·M-02·M-07,
 * Phase 9 목표 6·15·16·17).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5). 회차 범위는 그 위에 {@code assignment}(배치)가 한 번 더
 * 좁힌다 — 학원 안이어도 배치되지 않은 회차는 이 목록에 실리지 않는다({@code ManagerRunQueryService}
 * 참고).
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/manager/runs")
@RequiredArgsConstructor
public class ManagerRunController {

    private final ManagerRunQueryService managerRunQueryService;

    /**
     * 날짜를 주지 않으면 오늘이다(§4.1). {@code date} 를 손으로 적는 이유는
     * {@code StaffRunController#list} 와 같다 — {@code @ModelAttribute} 로 묶으면 스네이크 케이스
     * 파라미터가 조용히 안 붙는다.
     */
    @CanReadRoster
    @Operation(summary = "담당 회차 (RUN-01, M-02 · M-07 운행 카드)")
    @GetMapping
    public ApiResponse<List<ManagerRunResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @RequestParam(name = "date", required = false) String date) {
        return ApiResponse.ok(managerRunQueryService.list(requester, date));
    }
}
