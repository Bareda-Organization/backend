package src.backend.student.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageRoute;
import src.backend.student.dto.StopSuggestResponse;
import src.backend.student.query.StopSearchQueryService;

/**
 * 승하차지 주소 검색(§5.9) — 고정 노선 편성 화면이 정차지를 고르기 전에 쓰는 조회.
 *
 * <p>권한을 {@link CanManageRoute} 로 둔 것은 <b>이 검색의 유일한 소비자가 노선 편성</b>이기
 * 때문이다. 승하차지 자체의 관리 화면은 아직 없고, 학생 관리 권한으로 열면 노선을 못 고치는
 * 관계자에게 편성 화면의 일부만 동작하는 상태가 된다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/stops")
@RequiredArgsConstructor
public class StaffStopController {

    private final StopSearchQueryService stopSearchQueryService;

    /** 주소 자동완성 — 일부만 친 주소로 후보 여럿. 후보가 없으면 빈 목록이다(오류가 아니다). */
    @CanManageRoute
    @Operation(summary = "승하차지 주소 자동완성 — 후보 여럿과 각 후보 근처 기존 승하차지 (RTE-01)")
    @GetMapping("/suggest")
    public ApiResponse<StopSuggestResponse> suggest(@AuthenticationPrincipal AuthUser requester,
            @RequestParam @NotBlank @Size(max = 100) String query) {
        return ApiResponse.ok(stopSearchQueryService.suggest(requester, query));
    }
}
