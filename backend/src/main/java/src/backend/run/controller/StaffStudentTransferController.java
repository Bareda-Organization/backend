package src.backend.run.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import src.backend.run.command.TransferCommandService;
import src.backend.run.dto.TransferRequest;
import src.backend.run.dto.TransferResponse;

/**
 * 관계자 웹의 버스 간 이동 API(RTE-07, API_SPEC §5.8, Ruling 256).
 *
 * <p>학원 범위는 <b>토큰이 정한다</b>(§1.5) — {@code {id}}(학생)가 다른 학원 소속이면
 * {@code 404 STUDENT_NOT_FOUND}(존재 비노출, Ruling 163)다.
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/students")
@RequiredArgsConstructor
public class StaffStudentTransferController {

    private final TransferCommandService transferCommandService;

    /**
     * 버스 간 이동(§5.8) — 둘 중 한 회차라도 ②구간이면 {@code 403 CHANGE_WINDOW_CLOSED}, 도착 회차
     * 정원 초과는 {@code 409 CAPACITY_EXCEEDED}, 학생이 출발 회차 명단 밖이면
     * {@code 409 STUDENT_NOT_IN_RUN}, 도착 회차가 타 학원이면 {@code 403 ACADEMY_SCOPE_VIOLATION}.
     */
    @CanManageSchedule
    @Operation(summary = "수동 조정 · 버스 간 이동 (RTE-07, A-07)")
    @PostMapping("/{id}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transfer(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long id, @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok(transferCommandService.transfer(requester, id, request));
    }
}
