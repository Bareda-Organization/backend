package src.backend.student.dto;

import src.backend.student.entity.Student;

/**
 * 학생 요약 응답.
 * active 는 퇴원(비활성) 여부다 — 목록 화면이 재조회 없이 회색 배지를 그릴 수 있게 항상 내려준다(D-O).
 */
public record StudentResponse(
        Long id,
        Long tenantId,
        String name,
        Long userId,
        String phone,
        String photoUrl,
        Long assignedBusId,
        Long boardingStopId,
        String pickupAddress,
        String dropoffAddress,
        Double dropoffLat,
        Double dropoffLng,
        boolean active) {

    public static StudentResponse of(Student student) {
        return new StudentResponse(
                student.getId(),
                student.getTenant().getId(),
                student.getName(),
                student.getUserId(),
                student.getPhone(),
                student.getPhotoUrl(),
                student.getAssignedBus() != null ? student.getAssignedBus().getId() : null,
                student.getBoardingStop() != null ? student.getBoardingStop().getId() : null,
                student.getPickupAddress(),
                student.getDropoffAddress(),
                student.getDropoffLat(),
                student.getDropoffLng(),
                student.isActive());
    }
}
