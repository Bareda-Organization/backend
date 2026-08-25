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

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanRegisterDevice;
import src.backend.notification.command.DeviceCommandService;
import src.backend.notification.dto.DeviceRegisterRequest;
import src.backend.notification.dto.DeviceRegisterResponse;

/** 푸시 단말 등록·해지(API_SPEC §2.11). */
@RestController
public class DeviceController {

    private final DeviceCommandService deviceCommandService;

    public DeviceController(DeviceCommandService deviceCommandService) {
        this.deviceCommandService = deviceCommandService;
    }

    @CanRegisterDevice
    @PostMapping("/me/devices")
    public ResponseEntity<ApiResponse<DeviceRegisterResponse>> register(
            @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody DeviceRegisterRequest request) {
        DeviceRegisterResponse response = deviceCommandService.register(authUser.accountId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @CanRegisterDevice
    @DeleteMapping("/me/devices/{token}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthUser authUser, @PathVariable String token) {
        deviceCommandService.revoke(authUser.accountId(), token);
        return ResponseEntity.noContent().build();
    }
}
