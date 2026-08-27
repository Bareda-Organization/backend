package src.backend.bus.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.bus.command.BusCommandService;
import src.backend.bus.dto.BusListRequest;
import src.backend.bus.dto.BusRegisterRequest;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.BusUpdateRequest;
import src.backend.bus.query.BusQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageBus;

/**
 * 관계자 웹의 차량 관리 API(BUS-01~03 · A-11, API_SPEC §5.12).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — 경로·본문에 학원을 지정할 자리가 부재하고, 다른 학원의
 * 차량을 {@code {id}} 로 지목하면 {@code 404 BUS_NOT_FOUND} 다.
 */
@RestController
@RequestMapping("/staff/buses")
@RequiredArgsConstructor
public class StaffBusController {

    private final BusQueryService busQueryService;

    private final BusCommandService busCommandService;

    /** 차량 목록(BUS-01, §5.12). */
    @CanManageBus
    @GetMapping
    public ApiResponse<PageResponse<BusResponse>> list(@AuthenticationPrincipal AuthUser requester,
            @ModelAttribute BusListRequest request) {
        return ApiResponse.ok(busQueryService.list(requester, request));
    }

    /** 차량 등록(BUS-02, §5.12) — 응답의 {@code student_capacity} 는 서버 계산값이다. */
    @CanManageBus
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BusResponse> register(@AuthenticationPrincipal AuthUser requester,
            @Valid @RequestBody BusRegisterRequest request) {
        return ApiResponse.ok(busCommandService.register(requester, request));
    }

    /** 차량 수정(BUS-03, §5.12) — §1.9 대로 변경 후 자원 상태를 그대로 반환한다. */
    @CanManageBus
    @PatchMapping("/{id}")
    public ApiResponse<BusResponse> update(@AuthenticationPrincipal AuthUser requester, @PathVariable Long id,
            @Valid @RequestBody BusUpdateRequest request) {
        return ApiResponse.ok(busCommandService.update(requester, id, request));
    }
}
