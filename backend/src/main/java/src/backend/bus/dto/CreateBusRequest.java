package src.backend.bus.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 버스 생성 요청. tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 * driverId·routeId 는 생략 가능(추후 배차).
 */
public record CreateBusRequest(
        Long tenantId,
        @NotBlank String name,
        String plateNumber,
        @Positive int seatCapacity,
        Long driverId,
        Long routeId,
        LocalDate insuranceExpiry) {
}
