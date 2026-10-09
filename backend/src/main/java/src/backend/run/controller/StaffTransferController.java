package src.backend.run.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanManageRoute;
import src.backend.run.command.TransferCancelCommandService;

/** 관계자 웹의 이동 대기 기록 자원(§5.8.1) — 등록은 학생 경로({@link StaffStudentTransferController})에 있다. */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/staff/transfers")
@RequiredArgsConstructor
public class StaffTransferController {

    private final TransferCancelCommandService transferCancelCommandService;

    /**
     * 이동 대기 취소(§5.8.1) — 반영 전({@code staged}) 이동만 지운다. 없음·타 학원은
     * {@code 404 TRANSFER_NOT_FOUND}, 두 회차 중 하나라도 ① 구간이 끝났거나 이미 반영됐으면
     * {@code 403 CHANGE_WINDOW_CLOSED}.
     */
    @CanManageRoute
    @Operation(summary = "이동 대기 취소 (A-07 보조)")
    @DeleteMapping("/{transferId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthUser requester, @PathVariable Long transferId) {
        transferCancelCommandService.cancel(requester, transferId);
    }
}
