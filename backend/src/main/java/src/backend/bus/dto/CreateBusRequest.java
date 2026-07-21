package src.backend.bus.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 버스 생성 요청. tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 * driverId·routeId 는 생략 가능(추후 배차).
 */
public record CreateBusRequest(
        @Schema(example = "1", description = "소속 학원 id(한빛학원)") Long tenantId,
        @NotBlank @Schema(example = "4호차") String name,
        @Schema(example = "서울78마9012") String plateNumber,
        @Positive @Schema(example = "25") int seatCapacity,
        @Schema(example = "3", description = "담당 기사 user id(박기사)") Long driverId,
        @Schema(example = "1", description = "운행 노선 id(하원 A노선)") Long routeId,
        @Schema(example = "2027-06-30") LocalDate insuranceExpiry) {
}
