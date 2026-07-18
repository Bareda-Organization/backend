package src.backend.location.controller;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import jakarta.validation.Valid;
import src.backend.global.security.AuthUser;
import src.backend.location.command.LocationCommandService;
import src.backend.location.dto.LocationReportRequest;

/**
 * 실시간 위치 수신(STOMP). REST {@code POST /api/locations}와 같은 계약(LocationReportRequest)을
 * 그대로 쓰고, 저장 로직({@code LocationCommandService.reportSelf})도 그대로 재사용한다 —
 * 이 채널이 REST 를 "대체"하는 게 아니라, 같은 입력을 "연결 상태를 알 수 있는 통로"로 받는 것뿐이다.
 */
@Controller
public class LocationSocketController {

    private final LocationCommandService locationCommandService;

    public LocationSocketController(LocationCommandService locationCommandService) {
        this.locationCommandService = locationCommandService;
    }

    @MessageMapping("/location")
    public void report(Principal principal, @Payload @Valid LocationReportRequest request) {
        locationCommandService.reportSelf((AuthUser) principal, request);
    }
}
