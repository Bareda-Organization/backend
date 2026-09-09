package src.backend.student.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageWeeklyAddress;
import src.backend.student.command.WeeklyAddressCommandService;
import src.backend.student.dto.WeeklyAddressResponse;
import src.backend.student.dto.WeeklyAddressUpdateRequest;
import src.backend.student.query.WeeklyAddressQueryService;

/**
 * 학부모 앱의 요일별 등하원 주소 API(P-05 · STU-05·06, API_SPEC §3.7).
 *
 * <p><b>기본 주소 개념이 부재</b>하고 요일 × 방향이 노선 산출의 유일한 기준이다(C-12 · C-16) — 그래서
 * 경로가 {@code /students/{id}/address} 가 아니라 {@code weekly-address} 이고, 응답도 단건이 아니라
 * 칸 목록이다.
 *
 * <p>보호자는 토큰이 정한다(§1.5) — 요청에 어느 보호자인지 지정할 자리가 부재하다. {@code {id}} 가
 * 연결된 자녀인지는 {@code student.access} 의 단일 판정 지점이 본다.
 *
 * <p>계정 상태 게이트 애너테이션({@code @AllowedWhenPending} 등)은 붙지 않는다 — 승인된 학부모의
 * 기능이라 승인 대기·거절 계정이 닿을 이유가 부재하고, 허용 목록 밖으로 남아 {@code 403} 이 되는 것이
 * 사양이다(§1.4 · Ruling 145).
 */
@RestController
@RequestMapping("/students/{id}/weekly-address")
@RequiredArgsConstructor
public class WeeklyAddressController {

    private final WeeklyAddressQueryService weeklyAddressQueryService;

    private final WeeklyAddressCommandService weeklyAddressCommandService;

    /** 설정 화면의 초기 조회(§3.7 {@code GET}) — 응답 구조는 {@code PATCH} 와 같다. */
    @CanManageWeeklyAddress
    @GetMapping
    public ApiResponse<WeeklyAddressResponse> list(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId) {
        return ApiResponse.ok(weeklyAddressQueryService.list(authUser, studentId));
    }

    /**
     * 보낸 칸의 주소를 검증해 저장한다(§3.7 {@code PATCH}) — 검증 실패는 {@code 422} 이고 저장 보류다.
     *
     * <p>{@code 200} 인 것은 <b>새 자원을 만드는 요청이 아니기</b> 때문이다. 요일 × 방향 칸은 이미
     * 정해진 좌표계이고 이 요청은 그 칸의 값을 채우거나 고친다 — 같은 칸을 다시 보내면 덮어쓰기이므로
     * {@code 201} 이면 두 번째 호출부터 거짓이 된다.
     */
    @CanManageWeeklyAddress
    @PatchMapping
    public ApiResponse<WeeklyAddressResponse> replace(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("id") Long studentId, @Valid @RequestBody WeeklyAddressUpdateRequest request) {
        return ApiResponse.ok(weeklyAddressCommandService.replace(authUser, studentId, request));
    }
}
