package src.backend.rideevent.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * {@code getRosterRecords} 담당 기사·선탑자 인가 판정의 특성화 테스트(R1-①).
 * {@code BusCrewGuard} 로 뽑기 전 현재 동작(누가 통과·거부되는지)을 고정한다.
 */
class RideEventQueryServiceTest {

    private final RideEventRepository rideEventRepository = mock(RideEventRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final RideEventQueryService service = new RideEventQueryService(
            rideEventRepository, busRepository, studentRepository, studentGuardianRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void getRosterRecords_byUnassignedDriver_throwsForbidden() {
        AuthUser actor = authUser(999L, Role.DRIVER);
        Bus bus = bus(1L, user(300L, "driver@school.com", "박기사"), user(200L, "attendant@school.com", "김선탑"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.getRosterRecords(actor, 1L, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getRosterRecords_byAssignedAttendant_returnsRecords() {
        AuthUser actor = authUser(200L, Role.ATTENDANT);
        Bus bus = bus(1L, user(300L, "driver@school.com", "박기사"), user(200L, "attendant@school.com", "김선탑"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        LocalDate date = LocalDate.now();
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                1L, date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .willReturn(List.of(rideEvent(10L, 1L)));

        List<RideEventResponse> records = service.getRosterRecords(actor, 1L, date);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).id()).isEqualTo(10L);
    }

    private RideEvent rideEvent(Long id, Long busId) {
        RideEvent event = RideEvent.builder()
                .tenantId(TENANT_ID).studentId(1L).busId(busId).type(RideType.BOARD)
                .occurredAt(LocalDateTime.now()).source(RideSource.QR).build();
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private Bus bus(Long id, User driver, User attendant) {
        Bus bus = Bus.builder().tenant(tenant(TENANT_ID)).name("3호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private User user(Long id, String email, String name) {
        User user = User.builder().email(email).name(name).password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private AuthUser authUser(Long userId, Role role) {
        return new AuthUser(userId, "user@school.com", List.of(new AuthUser.Membership(TENANT_ID, role)));
    }
}
