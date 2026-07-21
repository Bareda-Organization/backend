package src.backend.sos.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * SOS 상태전이(OPEN→ACKNOWLEDGED→RESOLVED, 단방향·단계 건너뛰기 불가)·권한가드·에스컬레이션을
 * 우선 검증한다(G5). attendance/schedule과 달리 "확인 없이 바로 종료" 같은 단계 스킵을 막는
 * 게이트가 있어 별도 케이스로 추가한다.
 */
class SosCommandServiceTest {

    private final SosEventRepository sosEventRepository = mock(SosEventRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final SosCommandService service = new SosCommandService(
            sosEventRepository, studentRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;
    private static final Long STUDENT_ID = 10L;

    @Test
    void trigger_savesOpenEventAndPublishesEvent() {
        AuthUser studentUser = authUser(1L, TENANT_ID, Role.STUDENT);
        given(studentRepository.findByUserId(1L)).willReturn(Optional.of(student(STUDENT_ID, TENANT_ID)));
        given(sosEventRepository.save(any(SosEvent.class))).willAnswer(inv -> {
            SosEvent e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });

        SosEventResponse response = service.trigger(studentUser, new SosTriggerRequest(37.5, 127.0));

        assertThat(response.status()).isEqualTo(SosStatus.OPEN);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void acknowledge_open_transitionsToAcknowledged() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        SosEvent event = openEvent(1L, TENANT_ID);
        given(sosEventRepository.findById(1L)).willReturn(Optional.of(event));

        SosEventResponse response = service.acknowledge(admin, 1L);

        assertThat(response.status()).isEqualTo(SosStatus.ACKNOWLEDGED);
        assertThat(event.getAcknowledgedBy()).isEqualTo(200L);
    }

    @Test
    void acknowledge_alreadyAcknowledged_throwsConflict() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        SosEvent event = openEvent(1L, TENANT_ID);
        event.acknowledge(999L); // 이미 확인됨
        given(sosEventRepository.findById(1L)).willReturn(Optional.of(event));

        assertThatThrownBy(() -> service.acknowledge(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void resolve_stillOpen_throwsConflict_cannotSkipAcknowledge() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        SosEvent event = openEvent(1L, TENANT_ID); // 아직 확인 전(OPEN)
        given(sosEventRepository.findById(1L)).willReturn(Optional.of(event));

        assertThatThrownBy(() -> service.resolve(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void resolve_acknowledged_transitionsToResolved() {
        AuthUser admin = authUser(200L, TENANT_ID, Role.ACADEMY_ADMIN);
        SosEvent event = openEvent(1L, TENANT_ID);
        event.acknowledge(200L);
        given(sosEventRepository.findById(1L)).willReturn(Optional.of(event));

        SosEventResponse response = service.resolve(admin, 1L);

        assertThat(response.status()).isEqualTo(SosStatus.RESOLVED);
        assertThat(event.getResolvedBy()).isEqualTo(200L);
    }

    @Test
    void acknowledge_adminNotInTenant_throwsForbidden() {
        AuthUser admin = authUser(200L, 999L, Role.ACADEMY_ADMIN);
        SosEvent event = openEvent(1L, TENANT_ID);
        given(sosEventRepository.findById(1L)).willReturn(Optional.of(event));

        assertThatThrownBy(() -> service.acknowledge(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void escalateOverdue_publishesOneEventPerOverdueSos() {
        SosEvent overdue1 = openEvent(1L, TENANT_ID);
        SosEvent overdue2 = openEvent(2L, TENANT_ID);
        given(sosEventRepository.findByStatusAndOccurredAtBefore(any(SosStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of(overdue1, overdue2));

        service.escalateOverdue();

        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
    }

    @Test
    void escalateOverdue_noOverdueEvents_publishesNothing() {
        given(sosEventRepository.findByStatusAndOccurredAtBefore(any(SosStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        service.escalateOverdue();

        verify(eventPublisher, times(0)).publishEvent(any(Object.class));
    }

    private SosEvent openEvent(Long id, Long tenantId) {
        SosEvent event = SosEvent.builder()
                .tenantId(tenantId).studentId(STUDENT_ID).lat(37.5).lng(127.0)
                .occurredAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private Student student(Long id, Long tenantId) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        Student student = Student.builder().tenant(tenant).name("김민준").build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private AuthUser authUser(Long userId, Long tenantId, Role role) {
        return new AuthUser(userId, "u@school.com", List.of(new AuthUser.Membership(tenantId, role)));
    }
}
