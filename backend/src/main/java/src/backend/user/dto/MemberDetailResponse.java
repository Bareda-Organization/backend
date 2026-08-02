package src.backend.user.dto;

import java.util.ArrayList;
import java.util.List;

import src.backend.bus.entity.Bus;
import src.backend.student.entity.StudentGuardian;
import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;

/**
 * 구성원 상세 — 목록용 {@link MemberResponse} 에 <b>참조 현황</b>을 더한 것.
 *
 * <p>화면이 "이 사람을 지울 수 있는가"를 조회 한 번으로 판단하게 하려고 assignedBuses·guardedStudents 를
 * 함께 담는다(I-7). 이게 없으면 관리자가 삭제를 눌러 409 를 받고 나서야 이유를 안다.
 */
public record MemberDetailResponse(
        Long userId,
        String email,
        String name,
        String phone,
        String photoUrl,
        Role role,
        Long tenantId,
        List<BusRef> assignedBuses,
        List<StudentRef> guardedStudents) {

    /** 배정된 버스 한 대. asAttendant=true 면 선탑자로, false 면 기사로 배정된 것이다. */
    public record BusRef(Long busId, String plateNumber, boolean asAttendant) {}

    /** 보호자로 연결된 학생 한 명. */
    public record StudentRef(Long studentId, String name, String relation) {}

    public static MemberDetailResponse of(UserTenantRole membership,
                                          List<Bus> asDriver,
                                          List<Bus> asAttendant,
                                          List<StudentGuardian> guarded) {
        Long tenantId = membership.getTenant() != null ? membership.getTenant().getId() : null;
        List<BusRef> buses = new ArrayList<>();
        asDriver.forEach(b -> buses.add(new BusRef(b.getId(), b.getPlateNumber(), false)));
        asAttendant.forEach(b -> buses.add(new BusRef(b.getId(), b.getPlateNumber(), true)));
        // 형제가 다른 학원에 다닐 수 있다 — 요청자 학원의 연결만 보여준다.
        List<StudentRef> students = guarded.stream()
                .filter(sg -> tenantId != null && sg.getStudent().getTenant().getId().equals(tenantId))
                .map(sg -> new StudentRef(sg.getStudent().getId(), sg.getStudent().getName(), sg.getRelation()))
                .toList();
        return new MemberDetailResponse(
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getName(),
                membership.getUser().getPhone(),
                membership.getUser().getPhotoUrl(),
                membership.getRole(),
                tenantId,
                List.copyOf(buses),
                students);
    }
}
