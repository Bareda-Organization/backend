package src.backend.attendance.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.dto.AttendanceExceptionResponse;
import src.backend.attendance.dto.CreateAttendanceExceptionRequest;
import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.entity.AttendanceType;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.global.common.ApprovalStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 결석·휴원 신고 승인 워크플로 단위 테스트 — 상태전이(PENDING→APPROVED/REJECTED)·권한가드(보호자/테넌트)·
 * 중복처리(409) 케이스를 우선 검증한다(G5). Spring 컨텍스트 없이 순수 Mockito로 리포지토리를 대체한다
 * ({@link src.backend.global.event.TransactionalDomainEventRelayTest}와 동일한 패턴).
 */
class AttendanceCommandServiceTest {

    private final AttendanceExceptionRepository attendanceExceptionRepository = mock(AttendanceExceptionRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final AttendanceCommandService service = new AttendanceCommandService(
            attendanceExceptionRepository, studentGuardianRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;
    private static final Long STUDENT_ID = 10L;

    @Test
    void create_asGuardian_savesAndReturnsResponse() {
        AuthUser parent = authUser(100L, TENANT_ID, Role.PARENT);
        Student student = student(STUDENT_ID, TENANT_ID);
        given(studentGuardianRepository.findByGuardianId(100L))
                .willReturn(List.of(studentGuardian(student)));
        CreateAttendanceExceptionRequest req = new CreateAttendanceExceptionRequest(
                STUDENT_ID, AttendanceType.ABSENCE, LocalDate.now(), "감기");
        given(attendanceExceptionRepository.save(any(AttendanceException.class))).willAnswer(inv -> {
            AttendanceException e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });

        AttendanceExceptionResponse response = service.create(parent, req);

        assertThat(response.studentId()).isEqualTo(STUDENT_ID);
        assertThat(response.status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void create_notGuardian_throwsForbidden() {
        AuthUser parent = authUser(100L, TENANT_ID, Role.PARENT);
        given(studentGuardianRepository.findByGuardianId(100L)).willReturn(List.of()); // 이 학생의 보호자가 아님
        CreateAttendanceExceptionRequest req = new CreateAttendanceExceptionRequest(
                STUDENT_ID, AttendanceType.ABSENCE, LocalDate.now(), null);

        assertThatThrownBy(() -> service.create(parent, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void approve_pending_transitionsToApprovedAndPublishesEvent() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        AttendanceException exception = pendingException(1L, TENANT_ID);
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        AttendanceExceptionResponse response = service.approve(admin, 1L);

        assertThat(response.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(exception.getProcessedBy()).isEqualTo(200L);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void approve_alreadyProcessed_throwsConflict() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        AttendanceException exception = pendingException(1L, TENANT_ID);
        exception.approve(999L); // 이미 한 번 승인됨
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        assertThatThrownBy(() -> service.approve(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void approve_adminNotInTenant_throwsForbidden() {
        AuthUser admin = authUser(200L, 999L, Role.ACADEMY_ADMIN); // 다른 학원 관리자
        AttendanceException exception = pendingException(1L, TENANT_ID);
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        assertThatThrownBy(() -> service.approve(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void approve_platformAdmin_allowedRegardlessOfTenant() {
        AuthUser platformAdmin = authUser(300L, null, Role.PLATFORM_ADMIN);
        AttendanceException exception = pendingException(1L, TENANT_ID);
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        AttendanceExceptionResponse response = service.approve(platformAdmin, 1L);

        assertThat(response.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void reject_pending_transitionsToRejected() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        AttendanceException exception = pendingException(1L, TENANT_ID);
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        AttendanceExceptionResponse response = service.reject(admin, 1L);

        assertThat(response.status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void reject_alreadyProcessed_throwsConflict() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        AttendanceException exception = pendingException(1L, TENANT_ID);
        exception.reject(999L); // 이미 한 번 반려됨
        given(attendanceExceptionRepository.findById(1L)).willReturn(Optional.of(exception));

        assertThatThrownBy(() -> service.reject(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private AttendanceException pendingException(Long id, Long tenantId) {
        AttendanceException exception = AttendanceException.builder()
                .tenantId(tenantId).studentId(STUDENT_ID).type(AttendanceType.ABSENCE)
                .targetDate(LocalDate.now()).reason("사유").build();
        ReflectionTestUtils.setField(exception, "id", id);
        return exception;
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
