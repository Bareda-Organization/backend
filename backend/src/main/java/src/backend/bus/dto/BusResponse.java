package src.backend.bus.dto;

import java.time.LocalDate;

import src.backend.bus.entity.Bus;
import src.backend.user.entity.User;

/**
 * 버스 요약 응답.
 * onboard(배정 학생 수) vs assignCapacity(노선 배정 정원) 로 초과 배정 경고(overCapacity)를 판정한다.
 * seatCapacity(물리 좌석 수)와 assignCapacity(노선 배정 정원)는 별개 개념이다(projectInfo 6장).
 * 기사·선탑자 연락처와 사진까지 담아 목록 화면이 상세를 다시 부르지 않게 한다(요구 6).
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
        String driverPhone,
        String driverPhotoUrl,
        Long attendantId,
        String attendantName,
        String attendantPhone,
        String attendantPhotoUrl,
        Long routeId,
        String routeName,
        LocalDate insuranceExpiry) {

    public static BusResponse of(Bus bus, int onboard) {
        Integer assignCapacity = bus.getRoute() != null ? bus.getRoute().getAssignCapacity() : null;
        boolean over = assignCapacity != null && onboard > assignCapacity;
        User driver = bus.getDriver();          // 미배차 버스가 존재하므로 전부 null 가드
        User attendant = bus.getAttendant();
        return new BusResponse(
                bus.getId(),
                bus.getTenant().getId(),
                bus.getName(),
                bus.getPlateNumber(),
                bus.getSeatCapacity(),
                assignCapacity,
                onboard,
                over,
                driver != null ? driver.getId() : null,
                driver != null ? driver.getName() : null,
                driver != null ? driver.getPhone() : null,
                driver != null ? driver.getPhotoUrl() : null,
                attendant != null ? attendant.getId() : null,
                attendant != null ? attendant.getName() : null,
                attendant != null ? attendant.getPhone() : null,
                attendant != null ? attendant.getPhotoUrl() : null,
                bus.getRoute() != null ? bus.getRoute().getId() : null,
                bus.getRoute() != null ? bus.getRoute().getName() : null,
                bus.getInsuranceExpiry());
    }
}
