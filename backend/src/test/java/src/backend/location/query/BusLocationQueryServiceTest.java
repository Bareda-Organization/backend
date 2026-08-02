package src.backend.location.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 학부모 자녀 버스 위치 조회 단위 테스트(BE-9) — 입력이 보호자 계정뿐이라
 * 타 학부모 자녀의 버스 좌표가 결과에 섞일 수 없다는 격리 성질을 고정한다.
 */
class BusLocationQueryServiceTest {

    private final BusLocationRepository busLocationRepository = mock(BusLocationRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final BusLocationQueryService service =
            new BusLocationQueryService(busLocationRepository, busRepository, studentGuardianRepository);

    private static final Long TENANT_ID = 1L;
    private static final Long PARENT_ID = 40L;

    @Test
    void getChildrenBusLocations_otherParent_returnsEmpty() {
        AuthUser parent = authUser(PARENT_ID);
        given(studentGuardianRepository.findByGuardianId(PARENT_ID)).willReturn(List.of());

        List<BusLocationView> views = service.getChildrenBusLocations(parent);

        assertThat(views).isEmpty();
    }

    /** 형제자매가 같은 버스를 타면 같은 좌표가 두 번 나가면 안 된다. */
    @Test
    void getChildrenBusLocations_twoChildrenSameBus_returnsSingleEntry() {
        AuthUser parent = authUser(PARENT_ID);
        Bus bus = bus(7L, "3호차");
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(guardian(student(1L, bus)), guardian(student(2L, bus))));
        given(busLocationRepository.findLatest(7L)).willReturn(Optional.of(ping(7L, 37.5, 127.0)));

        List<BusLocationView> views = service.getChildrenBusLocations(parent);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).busId()).isEqualTo(7L);
        assertThat(views.get(0).busName()).isEqualTo("3호차");
        assertThat(views.get(0).lat()).isEqualTo(37.5);
        assertThat(views.get(0).lng()).isEqualTo(127.0);
        verify(busLocationRepository, times(1)).findLatest(7L);
    }

    /** 버스 미배정 자녀는 조회 대상에서 빠진다(빈 좌표를 만들어 내지 않는다). */
    @Test
    void getChildrenBusLocations_childWithoutBus_isSkipped() {
        AuthUser parent = authUser(PARENT_ID);
        Bus bus = bus(7L, "3호차");
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(guardian(student(1L, bus)), guardian(student(2L, null))));
        given(busLocationRepository.findLatest(7L)).willReturn(Optional.of(ping(7L, 37.5, 127.0)));

        List<BusLocationView> views = service.getChildrenBusLocations(parent);

        assertThat(views).extracting(BusLocationView::busId).containsExactly(7L);
        verify(busLocationRepository, never()).findLatest(null);
    }

    /** 격리 고정 — busId 를 입력받지 않으므로 다른 학부모 자녀의 버스는 결과에 들어올 수 없다. */
    @Test
    void getChildrenBusLocations_onlyOwnChildrenBuses() {
        AuthUser parent = authUser(PARENT_ID);
        Bus myBus = bus(7L, "3호차");
        Bus otherParentsBus = bus(9L, "5호차");
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(guardian(student(1L, myBus))));
        given(busLocationRepository.findLatest(7L)).willReturn(Optional.of(ping(7L, 37.5, 127.0)));
        given(busLocationRepository.findLatest(9L)).willReturn(Optional.of(ping(9L, 35.0, 129.0)));

        List<BusLocationView> views = service.getChildrenBusLocations(parent);

        assertThat(views).extracting(BusLocationView::busId)
                .containsExactly(7L)
                .doesNotContain(otherParentsBus.getId());
        verify(busLocationRepository, never()).findLatest(9L);
    }

    private Bus bus(Long id, String name) {
        Bus bus = Bus.builder().tenant(tenant()).name(name).seatCapacity(25).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Student student(Long id, Bus assignedBus) {
        Student student = Student.builder().tenant(tenant()).name("학생" + id)
                .assignedBus(assignedBus).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private StudentGuardian guardian(Student student) {
        return StudentGuardian.builder().student(student).guardian(parentUser()).relation("모").build();
    }

    private BusLocationPing ping(Long busId, double lat, double lng) {
        return new BusLocationPing(busId, TENANT_ID, lat, lng,
                LocalDateTime.of(2026, 8, 2, 8, 30), LocationOrigin.GPS);
    }

    private User parentUser() {
        User user = User.builder().email("parent@school.com").name("김보호").password("x").build();
        ReflectionTestUtils.setField(user, "id", PARENT_ID);
        return user;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private AuthUser authUser(Long userId) {
        return new AuthUser(userId, "parent@school.com", List.of(new AuthUser.Membership(TENANT_ID, Role.PARENT)));
    }
}
