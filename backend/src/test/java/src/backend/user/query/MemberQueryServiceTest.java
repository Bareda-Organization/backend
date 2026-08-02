package src.backend.user.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.dto.StudentResponse;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.dto.MemberDetailResponse;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 구성원 상세 조회 단위 테스트 (BE-12).
 * 검증 대상은 두 가지 — 참조 현황을 한 번에 담는가(I-7), 그리고 학원 격리가 멤버십 우선 조회로 보장되는가.
 */
class MemberQueryServiceTest {

    private final UserTenantRoleRepository userTenantRoleRepository = mock(UserTenantRoleRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final MemberQueryService service =
            new MemberQueryService(userTenantRoleRepository, busRepository, studentGuardianRepository);

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 999L;
    private static final Long TARGET_ID = 7L;

    @Test
    void get_includesAssignedBusesAndGuardedStudents() {
        given(userTenantRoleRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .willReturn(Optional.of(membership(Role.DRIVER)));
        given(busRepository.findByDriverIdAndTenantId(TARGET_ID, TENANT_ID))
                .willReturn(List.of(bus(1L, "3호차", "12가3456", TENANT_ID)));
        given(busRepository.findByAttendantIdAndTenantId(TARGET_ID, TENANT_ID))
                .willReturn(List.of(bus(2L, "5호차", "34나5678", TENANT_ID)));
        given(studentGuardianRepository.findByGuardianId(TARGET_ID)).willReturn(List.of(
                guardian(10L, "김민준", TENANT_ID, "모"),
                guardian(11L, "타학원형제", OTHER_TENANT_ID, "모")));   // 형제가 다른 학원 — 제외돼야 한다

        MemberDetailResponse detail = service.get(admin(), TARGET_ID, null);

        assertThat(detail.assignedBuses()).extracting(MemberDetailResponse.BusRef::busId).containsExactly(1L, 2L);
        assertThat(detail.assignedBuses()).extracting(MemberDetailResponse.BusRef::asAttendant)
                .containsExactly(false, true);
        assertThat(detail.guardedStudents()).extracting(MemberDetailResponse.StudentRef::studentId)
                .containsExactly(10L);   // 다른 학원 형제는 빠진다
    }

    /** 다른 학원 구성원은 존재 여부조차 알려주지 않는다 — 403 이 아니라 404 다. */
    @Test
    void get_otherTenantMember_throwsNotFound() {
        given(userTenantRoleRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(admin(), TARGET_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    /**
     * 역방향 조회 — 형제가 다른 학원에 다닐 수 있어 요청자 학원 것만 돌려준다(BE-14).
     * 비활성 학생은 여기서 걸러내지 않는다: 관리자가 "연결된 전부"를 봐야 연결을 정리할 수 있다.
     */
    @Test
    void studentsOf_otherTenantSibling_isExcluded() {
        given(userTenantRoleRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .willReturn(Optional.of(membership(Role.PARENT)));
        given(studentGuardianRepository.findByGuardianId(TARGET_ID)).willReturn(List.of(
                guardian(10L, "김민준", TENANT_ID, "모"),
                guardian(11L, "타학원형제", OTHER_TENANT_ID, "모")));

        List<StudentResponse> children = service.studentsOf(admin(), TARGET_ID, null);

        assertThat(children).extracting(StudentResponse::id).containsExactly(10L);
    }

    /** 다른 학원 학부모 id 로는 자녀 목록도 볼 수 없다 — 격리는 멤버십 우선 조회 한 곳에서 끝난다. */
    @Test
    void studentsOf_otherTenantGuardian_throwsNotFound() {
        given(userTenantRoleRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.studentsOf(admin(), TARGET_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ── fixtures ──

    private UserTenantRole membership(Role role) {
        User user = User.builder().email("driver@school.com").password("{bcrypt}x").name("김기사")
                .phone("010-1111-2222").photoUrl("https://cdn/driver.png").build();
        ReflectionTestUtils.setField(user, "id", TARGET_ID);
        UserTenantRole membership = UserTenantRole.builder().user(user).tenant(tenant(TENANT_ID)).role(role).build();
        ReflectionTestUtils.setField(membership, "id", 500L);
        return membership;
    }

    private Bus bus(Long id, String name, String plateNumber, Long tenantId) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name(name).plateNumber(plateNumber).seatCapacity(25).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private StudentGuardian guardian(Long studentId, String studentName, Long tenantId, String relation) {
        Student student = Student.builder().tenant(tenant(tenantId)).name(studentName).build();
        ReflectionTestUtils.setField(student, "id", studentId);
        return StudentGuardian.builder().student(student).relation(relation).build();
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private AuthUser admin() {
        return new AuthUser(100L, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }
}
