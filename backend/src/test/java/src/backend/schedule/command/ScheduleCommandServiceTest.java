package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.schedule.dto.CreateScheduleChangeRequest;
import src.backend.schedule.dto.ScheduleChangeRequestResponse;
import src.backend.schedule.entity.ScheduleChangeRequest;
import src.backend.schedule.repository.spec.ScheduleChangeRequestRepository;
import src.backend.global.common.ApprovalStatus;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 등하원 시간 변경 요청 승인 워크플로 단위 테스트 — 상태전이(PENDING→APPROVED/REJECTED)·권한가드(보호자/테넌트)·
 * 중복처리(409) 케이스를 우선 검증한다(G5). {@link src.backend.attendance.command.AttendanceCommandServiceTest}와
 * 동일한 패턴(순수 Mockito, Spring 컨텍스트 없음).
 */
class ScheduleCommandServiceTest {

    private final ScheduleChangeRequestRepository scheduleChangeRequestRepository = mock(ScheduleChangeRequestRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final ScheduleCommandService service = new ScheduleCommandService(
            scheduleChangeRequestRepository, studentGuardianRepository, studentRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;
    private static final Long STUDENT_ID = 10L;

    @Test
    void create_asGuardian_savesAndReturnsResponse() {
        AuthUser parent = authUser(100L, TENANT_ID, Role.PARENT);
        Student student = student(STUDENT_ID, TENANT_ID);
        given(studentGuardianRepository.findByGuardianId(100L)).willReturn(List.of(studentGuardian(student)));
        CreateScheduleChangeRequest req = new CreateScheduleChangeRequest(
                STUDENT_ID, LocalDate.now(), LocalTime.of(17, 30), "병원 진료");
        given(scheduleChangeRequestRepository.save(any(ScheduleChangeRequest.class))).willAnswer(inv -> {
            ScheduleChangeRequest e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });

        ScheduleChangeRequestResponse response = service.create(parent, req);

        assertThat(response.studentId()).isEqualTo(STUDENT_ID);
        assertThat(response.status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void create_notGuardian_throwsForbidden() {
        AuthUser parent = authUser(100L, TENANT_ID, Role.PARENT);
        given(studentGuardianRepository.findByGuardianId(100L)).willReturn(List.of());
        CreateScheduleChangeRequest req = new CreateScheduleChangeRequest(
                STUDENT_ID, LocalDate.now(), LocalTime.of(17, 30), null);

        assertThatThrownBy(() -> service.create(parent, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void approve_pending_transitionsToApprovedAndPublishesEvent() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        ScheduleChangeRequest request = pendingRequest(1L, TENANT_ID);
        given(scheduleChangeRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student(STUDENT_ID, TENANT_ID)));

        ScheduleChangeRequestResponse response = service.approve(admin, 1L);

        assertThat(response.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(request.getProcessedBy()).isEqualTo(200L);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void approve_alreadyProcessed_throwsConflict() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        ScheduleChangeRequest request = pendingRequest(1L, TENANT_ID);
        request.approve(999L); // 이미 한 번 승인됨
        given(scheduleChangeRequestRepository.findById(1L)).willReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approve(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void approve_adminNotInTenant_throwsForbidden() {
        AuthUser admin = authUser(200L, 999L, Role.ACADEMY_ADMIN);
        ScheduleChangeRequest request = pendingRequest(1L, TENANT_ID);
        given(scheduleChangeRequestRepository.findById(1L)).willReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approve(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void reject_pending_transitionsToRejectedAndPublishesEvent() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        ScheduleChangeRequest request = pendingRequest(1L, TENANT_ID);
        given(scheduleChangeRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student(STUDENT_ID, TENANT_ID)));

        ScheduleChangeRequestResponse response = service.reject(admin, 1L);

        assertThat(response.status()).isEqualTo(ApprovalStatus.REJECTED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void reject_alreadyProcessed_throwsConflict() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        ScheduleChangeRequest request = pendingRequest(1L, TENANT_ID);
        request.reject(999L); // 이미 한 번 반려됨
        given(scheduleChangeRequestRepository.findById(1L)).willReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private ScheduleChangeRequest pendingRequest(Long id, Long tenantId) {
        ScheduleChangeRequest request = ScheduleChangeRequest.builder()
                .tenantId(tenantId).studentId(STUDENT_ID)
                .requestedDate(LocalDate.now()).requestedTime(LocalTime.of(17, 30)).reason("사유").build();
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    private Student student(Long id, Long tenantId) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        Student student = Student.builder().tenant(tenant).name("김민준").build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private StudentGuardian studentGuardian(Student student) {
        User guardian = User.builder().email("parent@school.com").name("이부모").password("x").build();
        return StudentGuardian.builder().student(student).guardian(guardian).relation("모").build();
    }

    private AuthUser authUser(Long userId, Long tenantId, Role role) {
        return new AuthUser(userId, "u@school.com", List.of(new AuthUser.Membership(tenantId, role)));
    }
}
