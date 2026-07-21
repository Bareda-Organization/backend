package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.dto.StudentResponse;
import src.backend.student.dto.UpdateDropoffRequest;
import src.backend.student.dto.UpdateStudentAssignmentRequest;
import src.backend.student.entity.Student;
import src.backend.student.event.StudentAssignmentChangedEvent;
import src.backend.student.event.StudentDropoffChangedEvent;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.repository.spec.UserRepository;

/**
 * 학생 재배정/하차지 변경 단위 테스트 — F2(이벤트 발행 조건: no-op 스킵·old/new busId·dropoff는
 * 배정 버스가 있을 때만)를 우선 검증한다(G5 2차). 실제 replan 동작은 curl E2E로 이미 검증됐고
 * (트래커 §2 F2 참조), 여기서는 이벤트 발행 여부·페이로드 자체의 단위 커버리지를 추가한다.
 */
class StudentCommandServiceTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final StudentCommandService service = new StudentCommandService(
            studentRepository, studentGuardianRepository, tenantRepository, busRepository,
            stopRepository, userRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;
    private static final Long STUDENT_ID = 10L;

    @Test
    void updateAssignment_noop_publishesNothing() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, bus(1L, TENANT_ID));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));

        StudentResponse response = service.updateAssignment(admin, STUDENT_ID, new UpdateStudentAssignmentRequest(null, null));

        assertThat(response.assignedBusId()).isEqualTo(1L); // 변경 없음
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void updateAssignment_busChanged_publishesEventWithOldAndNewBusId() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, bus(1L, TENANT_ID)); // 현재 bus1
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));
        given(busRepository.findById(2L)).willReturn(Optional.of(bus(2L, TENANT_ID)));

        service.updateAssignment(admin, STUDENT_ID, new UpdateStudentAssignmentRequest(2L, null));

        ArgumentCaptor<StudentAssignmentChangedEvent> captor = ArgumentCaptor.forClass(StudentAssignmentChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().oldBusId()).isEqualTo(1L);
        assertThat(captor.getValue().newBusId()).isEqualTo(2L);
    }

    @Test
    void updateAssignment_boardingStopOnlyChanged_oldAndNewBusIdAreSame() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, bus(1L, TENANT_ID)); // 버스는 그대로
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));
        given(stopRepository.findById(5L)).willReturn(Optional.of(stop(5L, TENANT_ID)));

        service.updateAssignment(admin, STUDENT_ID, new UpdateStudentAssignmentRequest(null, 5L));

        ArgumentCaptor<StudentAssignmentChangedEvent> captor = ArgumentCaptor.forClass(StudentAssignmentChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().oldBusId()).isEqualTo(captor.getValue().newBusId());
    }

    @Test
    void updateAssignment_busInOtherTenant_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, null);
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));
        given(busRepository.findById(2L)).willReturn(Optional.of(bus(2L, 999L))); // 다른 학원 버스

        assertThatThrownBy(() -> service.updateAssignment(admin, STUDENT_ID, new UpdateStudentAssignmentRequest(2L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void updateDropoff_withAssignedBus_publishesEvent() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, bus(1L, TENANT_ID));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));

        service.updateDropoff(admin, STUDENT_ID, new UpdateDropoffRequest("주소", 37.5, 127.0));

        ArgumentCaptor<StudentDropoffChangedEvent> captor = ArgumentCaptor.forClass(StudentDropoffChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().busId()).isEqualTo(1L);
    }

    @Test
    void updateDropoff_unassignedStudent_publishesNothing() {
        AuthUser admin = authUser(TENANT_ID);
        Student student = student(STUDENT_ID, TENANT_ID, null); // 배정 버스 없음
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));

        StudentResponse response = service.updateDropoff(admin, STUDENT_ID, new UpdateDropoffRequest(null, 37.5, 127.0));

        assertThat(response.dropoffLat()).isEqualTo(37.5);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void updateAssignment_otherTenantAdmin_throwsForbidden() {
        AuthUser otherAdmin = authUser(999L);
        Student student = student(STUDENT_ID, TENANT_ID, null);
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student));

        assertThatThrownBy(() -> service.updateAssignment(otherAdmin, STUDENT_ID, new UpdateStudentAssignmentRequest(1L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private Student student(Long id, Long tenantId, Bus assignedBus) {
        Student student = Student.builder().tenant(tenant(tenantId)).name("김민준").assignedBus(assignedBus).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private Bus bus(Long id, Long tenantId) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("버스" + id).seatCapacity(25).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Stop stop(Long id, Long tenantId) {
        Route route = Route.builder().tenant(tenant(tenantId)).name("A노선").assignCapacity(25).build();
        Stop stop = Stop.builder().route(route).name("정류장").seq(1).lat(37.5).lng(127.0).build();
        ReflectionTestUtils.setField(stop, "id", id);
        return stop;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private AuthUser authUser(Long tenantId) {
        return new AuthUser(100L, "admin@school.com", List.of(new AuthUser.Membership(tenantId, Role.ACADEMY_ADMIN)));
    }
}
