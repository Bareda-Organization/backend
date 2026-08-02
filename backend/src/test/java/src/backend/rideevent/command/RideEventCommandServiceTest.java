package src.backend.rideevent.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 승하차 기록·정정의 인가 단위 테스트(BE-3).
 * 기록은 담당 선탑자 본인만(I-2), 정정은 관리자 또는 그 버스의 담당 선탑자만 가능한지 확인한다.
 */
class RideEventCommandServiceTest {

    private final RideEventRepository rideEventRepository = mock(RideEventRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final RideEventCommandService service = new RideEventCommandService(
            rideEventRepository, busRepository, studentRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;

    @Test
    void record_byAttendantOfAnotherBus_throwsForbidden() {
        AuthUser actor = authUser(999L, Role.ATTENDANT);
        Bus bus = bus(1L, user(200L, "attendant@school.com", "김선탑"), null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        RecordRideRequest req = new RecordRideRequest(1L, 1L, RideType.BOARD, null, null, null);

        assertThatThrownBy(() -> service.record(actor, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void record_byDriverOfThatBus_throwsForbidden() {
        AuthUser actor = authUser(300L, Role.DRIVER);
        Bus bus = bus(1L, user(200L, "attendant@school.com", "김선탑"),
                user(300L, "driver@school.com", "박기사"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        RecordRideRequest req = new RecordRideRequest(1L, 1L, RideType.BOARD, null, null, null);

        assertThatThrownBy(() -> service.record(actor, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void correct_byAttendantOfAnotherBusInSameTenant_throwsForbidden() {
        AuthUser actor = authUser(999L, Role.ATTENDANT); // 같은 학원 소속이지만 담당 버스가 아니다
        RideEvent original = rideEvent(10L, TENANT_ID, 1L);
        given(rideEventRepository.findById(10L)).willReturn(Optional.of(original));
        given(busRepository.findById(1L))
                .willReturn(Optional.of(bus(1L, user(200L, "attendant@school.com", "김선탑"), null)));
        CorrectionRequest req = new CorrectionRequest(RideType.BOARD, null, null, null, null);

        assertThatThrownBy(() -> service.correct(actor, 10L, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private Bus bus(Long id, User attendant, User driver) {
        Bus bus = Bus.builder().tenant(tenant(TENANT_ID)).name("3호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private RideEvent rideEvent(Long id, Long tenantId, Long busId) {
        RideEvent event = RideEvent.builder()
                .tenantId(tenantId).studentId(1L).busId(busId).type(RideType.BOARD)
                .occurredAt(LocalDateTime.now()).source(RideSource.MANUAL).build();
        ReflectionTestUtils.setField(event, "id", id);
        return event;
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
