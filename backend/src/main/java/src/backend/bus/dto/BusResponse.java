package src.backend.bus.dto;

import java.time.LocalDate;

import src.backend.bus.entity.Bus;

/**
 * 버스 요약 응답.
 * onboard(배정 학생 수) vs assignCapacity(노선 배정 정원) 로 초과 배정 경고(overCapacity)를 판정한다.
 * seatCapacity(물리 좌석 수)와 assignCapacity(노선 배정 정원)는 별개 개념이다(projectInfo 6장).
 */
public record BusResponse(
        Long id,
        Long tenantId,
        String name,
        String plateNumber,
        int seatCapacity,
        Integer assignCapacity,
        int onboard,
        boolean overCapacity,
        Long driverId,
        String driverName,
        Long routeId,
        String routeName,
        LocalDate insuranceExpiry) {

    public static BusResponse of(Bus bus, int onboard) {
        Integer assignCapacity = bus.getRoute() != null ? bus.getRoute().getAssignCapacity() : null;
        boolean over = assignCapacity != null && onboard > assignCapacity;
        return new BusResponse(
                bus.getId(),
                bus.getTenant().getId(),
                bus.getName(),
                bus.getPlateNumber(),
                bus.getSeatCapacity(),
                assignCapacity,
                onboard,
                over,
                bus.getDriver() != null ? bus.getDriver().getId() : null,
                bus.getDriver() != null ? bus.getDriver().getName() : null,
                bus.getRoute() != null ? bus.getRoute().getId() : null,
                bus.getRoute() != null ? bus.getRoute().getName() : null,
                bus.getInsuranceExpiry());
    }
}
