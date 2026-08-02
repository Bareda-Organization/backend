package src.backend.drivesession.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.query.AttendanceQueryService;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.DriveSessionRosterEntry;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Stop;
import src.backend.routing.domain.RouteDirection;
import src.backend.student.entity.Student;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 운행 세션 조회의 인가·명단 변환 단위 테스트(BE-3).
 * 선탑자에게 사슬(운행 이력 → 명단)이 열렸는지와, 명단이 photoUrl·D-K 좌표 우선순위를 지키는지 확인한다.
 */
class DriveSessionQueryServiceTest {

    private final DriveSessionRepository driveSessionRepository = mock(DriveSessionRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final AttendanceQueryService attendanceQueryService = mock(AttendanceQueryService.class);

    private final DriveSessionQueryService service = new DriveSessionQueryService(
            driveSessionRepository, busRepository, attendanceQueryService);

    private static final Long TENANT_ID = 1L;

    @Test
    void getBusHistory_byAttendantOfAnotherBus_throwsForbidden() {
        AuthUser actor = authUser(999L, Role.ATTENDANT);
        Bus bus = bus(1L, user(200L, "attendant@school.com", "김선탑"), user(300L, "driver@school.com", "박기사"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.getBusHistory(actor, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getBusHistory_byAssignedAttendant_returnsHistory() {
        AuthUser actor = authUser(200L, Role.ATTENDANT);
        Bus bus = bus(1L, user(200L, "attendant@school.com", "김선탑"), user(300L, "driver@school.com", "박기사"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(driveSessionRepository.findByBusIdOrderByStartedAtDesc(1L))
                .willReturn(List.of(session(5L, 1L, 300L, RouteDirection.PICKUP)));

        List<DriveSessionResponse> history = service.getBusHistory(actor, 1L);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).id()).isEqualTo(5L);
    }

    @Test
    void getRoster_pickup_includesPhotoUrlAndPrefersPickupCoords() {
        AuthUser actor = authUser(300L, Role.DRIVER); // 본인이 시작한 운행
        DriveSession session = session(5L, 1L, 300L, RouteDirection.PICKUP);
        given(driveSessionRepository.findById(5L)).willReturn(Optional.of(session));
        Student student = student(1L, "김민준", "https://cdn.example.com/students/1.jpg",
                stop("정류장 A", 37.5010, 127.0275));
        student.updatePickup("자택 앞", 37.5002, 127.0262);
        given(attendanceQueryService.getActiveRoster(1L, session.getServiceDate()))
                .willReturn(List.of(student));

        List<DriveSessionRosterEntry> roster = service.getRoster(actor, 5L);

        assertThat(roster).hasSize(1);
        assertThat(roster.get(0).photoUrl()).isEqualTo("https://cdn.example.com/students/1.jpg");
        assertThat(roster.get(0).location()).isEqualTo("자택 앞");
        assertThat(roster.get(0).lat()).isEqualTo(37.5002); // boardingStop(37.5010) 이 아니다 — D-K
        assertThat(roster.get(0).lng()).isEqualTo(127.0262);
    }

    private DriveSession session(Long id, Long busId, Long driverId, RouteDirection direction) {
        DriveSession session = DriveSession.builder()
                .tenantId(TENANT_ID).busId(busId).driverId(driverId)
                .direction(direction).serviceDate(LocalDate.now()).build();
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    private Bus bus(Long id, User attendant, User driver) {
        Bus bus = Bus.builder().tenant(tenant(TENANT_ID)).name("3호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Student student(Long id, String name, String photoUrl, Stop boardingStop) {
        Student student = Student.builder().tenant(tenant(TENANT_ID)).name(name)
                .photoUrl(photoUrl).boardingStop(boardingStop).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private Stop stop(String name, double lat, double lng) {
        Stop stop = Stop.builder().name(name).seq(1).lat(lat).lng(lng).build();
        ReflectionTestUtils.setField(stop, "id", 1L);
        return stop;
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
