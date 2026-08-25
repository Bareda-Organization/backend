package src.backend.notification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanRegisterDevice;
import src.backend.global.security.gate.AllowedWhenPending;
import src.backend.notification.command.DeviceCommandService;
import src.backend.notification.dto.DeviceRegisterRequest;
import src.backend.notification.dto.DeviceRegisterResponse;

/**
 * 푸시 단말 등록·해지(API_SPEC §2.11). {@code pending} 계정도 대기 중 알림을 받을 단말을 등록해야
 * 해 {@code @AllowedWhenPending} 을 인가 축({@code @CanRegisterDevice})과 함께 붙인다 — 두 축은
 * 겸하지 않되 한 핸들러가 각 축에서 하나씩 갖는 것은 허용된다(SignupController 선례, Task 4).
 */
@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceCommandService deviceCommandService;

    @CanRegisterDevice
    @AllowedWhenPending
    @PostMapping("/me/devices")
    public ResponseEntity<ApiResponse<DeviceRegisterResponse>> register(
            @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody DeviceRegisterRequest request) {
        DeviceRegisterResponse response = deviceCommandService.register(authUser.accountId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @CanRegisterDevice
    @AllowedWhenPending
    @DeleteMapping("/me/devices/{token}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthUser authUser, @PathVariable String token) {
        deviceCommandService.revoke(authUser.accountId(), token);
        return ResponseEntity.noContent().build();
    }
}
