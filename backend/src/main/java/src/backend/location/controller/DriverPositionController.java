package src.backend.location.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.security.AuthUser;
import src.backend.location.command.RunPositionCommandService;
import src.backend.location.dto.RunPositionRequest;

/**
 * 기사 단말의 위치 송신 API(API_SPEC §4.12, LOC-01, 목표 1·2). {@code /runs} 아래 다른 기사·동승자
 * 단말 API(운행 시작·도착·변경 확인)와 같은 표면이지만, 이 좌석 소유 범위({@code location/})를
 * 지키기 위해 {@code DriverRunController} 를 확장하지 않고 별도 컨트롤러로 둔다 — 두 파일이 각자
 * 소유자만 고치면 되므로 병렬 좌석 간 충돌이 나지 않는다.
 */
@Tag(name = ApiTags.MANAGER)
@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class DriverPositionController {

    private final RunPositionCommandService runPositionCommandService;

    /** 위치 수신(§4.12) — {@code moving} 상태에서만 성공하며 저장소만 늘리고 응답 본문은 없다. */
    @Operation(summary = "위치 업로드 (LOC-01)")
    @PostMapping("/{runId}/position")
    public ResponseEntity<Void> receive(@AuthenticationPrincipal AuthUser requester,
            @PathVariable Long runId, @Valid @RequestBody RunPositionRequest request) {
        runPositionCommandService.receive(requester, runId, request);
        return ResponseEntity.noContent().build();
    }
}
