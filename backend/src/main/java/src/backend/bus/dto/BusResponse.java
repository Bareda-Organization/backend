package src.backend.bus.dto;

import src.backend.bus.entity.Bus;

/**
 * 차량 응답(API_SPEC §5.12) — 목록·등록·수정이 함께 쓴다. 관계자 웹 전용이라 역할별로 가르지 않는다
 * (차량은 학부모·매니저 앱 응답에 실리지 않는다).
 *
 * @param studentCapacity 서버가 계산한 학생 탑승 가능 인원. 요청이 이 값을 실어 보내도 무시된다(§5.12)
 */
public record BusResponse(Long id, String busNo, String plateNo, int capacity, int studentCapacity,
        boolean operable) {

    public static BusResponse from(Bus bus) {
        return new BusResponse(bus.getId(), bus.getBusNo(), bus.getPlateNo(), bus.getCapacity(),
                bus.getStudentCapacity(), bus.isOperable());
    }
}
