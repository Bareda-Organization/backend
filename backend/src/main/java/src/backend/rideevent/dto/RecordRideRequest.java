package src.backend.rideevent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.rideevent.entity.RideType;

/**
 * 기사 승/하차 기록 요청. stopId·lat·lng 는 생략 가능(생략 시 학생 기본 승차 정류장 사용).
 */
public record RecordRideRequest(
        @NotNull @Schema(example = "1", description = "버스 id(3호차)") Long busId,
        @NotNull @Schema(example = "1", description = "학생 id(김민준)") Long studentId,
        @NotNull @Schema(example = "BOARD") RideType type,
        @Schema(example = "1", description = "정류장 id(정류장 A) — 생략 시 학생 기본 승차 정류장") Long stopId,
        @Schema(example = "37.5010") Double lat,
        @Schema(example = "127.0275") Double lng) {
}
