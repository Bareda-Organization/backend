package src.backend.rideevent.dto;

import jakarta.validation.constraints.NotNull;
import src.backend.rideevent.entity.RideType;

/**
 * 기사 승/하차 기록 요청. stopId·lat·lng 는 생략 가능(생략 시 학생 기본 승차 정류장 사용).
 */
public record RecordRideRequest(
        @NotNull Long busId,
        @NotNull Long studentId,
        @NotNull RideType type,
        Long stopId,
        Double lat,
        Double lng) {
}
