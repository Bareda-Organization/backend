package src.backend.student.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
 * 보호자 판정 단위 테스트 — attendance·schedule 세 서비스에 복붙돼 있던 판정 로직이
 * {@link GuardianAccess} 로 모인 뒤에도 판정 자체(자녀/비자녀/무연결)는 여기서 검증한다.
 */
class GuardianAccessTest {

    private static final Long TENANT_ID = 1L;
    private static final Long PARENT_ID = 100L;
    private static final Long STUDENT_ID = 10L;

    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final GuardianAccess guardianAccess = new GuardianAccess(studentGuardianRepository);

    @Test
    void requireGuardianOf_isGuardian_returnsStudent() {
        AuthUser parent = authUser(PARENT_ID, Role.PARENT);
        Student student = student(STUDENT_ID);
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(studentGuardian(student)));

        Student result = guardianAccess.requireGuardianOf(parent, STUDENT_ID);

        assertThat(result.getId()).isEqualTo(STUDENT_ID);
    }

    @Test
    void requireGuardianOf_notGuardianOfThatStudent_throwsForbidden() {
        AuthUser parent = authUser(PARENT_ID, Role.PARENT);
        Student otherStudent = student(999L); // 다른 자녀는 있지만 이 학생은 아니다
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(studentGuardian(otherStudent)));

        BusinessException ex = catchThrowableOfType(BusinessException.class,
                () -> guardianAccess.requireGuardianOf(parent, STUDENT_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex).hasMessageContaining("자녀가 아닙니다");
    }

    @Test
    void requireGuardianOf_noLinkedStudentAtAll_throwsForbidden() {
        AuthUser parent = authUser(PARENT_ID, Role.PARENT);
        given(studentGuardianRepository.findByGuardianId(PARENT_ID)).willReturn(List.of());

        BusinessException ex = catchThrowableOfType(BusinessException.class,
                () -> guardianAccess.requireGuardianOf(parent, STUDENT_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex).hasMessageContaining("자녀가 아닙니다");
    }

    @Test
    void requireGuardianOf_deactivatedStudent_throwsForbiddenWithDeactivatedMessage() {
        AuthUser parent = authUser(PARENT_ID, Role.PARENT);
        Student student = student(STUDENT_ID);
        student.deactivate(); // 퇴원 처리 — active=false
        given(studentGuardianRepository.findByGuardianId(PARENT_ID))
                .willReturn(List.of(studentGuardian(student)));

        BusinessException ex = catchThrowableOfType(BusinessException.class,
                () -> guardianAccess.requireGuardianOf(parent, STUDENT_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex).hasMessageContaining("퇴원");
    }

    private Student student(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        // Student.builder() 는 active 필드를 받지 않는다 — 엔티티 필드 기본값(active=true, 재학 중)이
        // 그대로 적용된다는 전제로 이 테스트들은 "재학 중인 자녀" 케이스를 검증한다.
        Student student = Student.builder().tenant(tenant).name("김민준").build();
        ReflectionTestUtils.setField(student, "id", id);
        assertThat(student.isActive()).isTrue();
        return student;
    }

    private StudentGuardian studentGuardian(Student student) {
        User guardian = User.builder().email("parent@school.com").name("이부모").password("x").build();
        return StudentGuardian.builder().student(student).guardian(guardian).relation("모").build();
    }

    private AuthUser authUser(Long userId, Role role) {
        return new AuthUser(userId, "u@school.com", List.of(new AuthUser.Membership(TENANT_ID, role)));
    }
}
