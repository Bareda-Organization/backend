package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.security.AuthUser;
import src.backend.student.dto.StudentResponse;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * 학생 목록의 활성 필터(I-9) 단위 테스트.
 * 기본값이 "전부"로 새면 퇴원생이 명단·배차·시뮬레이션에 계속 끼어들기 때문에,
 * 어떤 리포지토리 메서드를 부르는지까지 못 박아 둔다.
 */
class StudentQueryServiceTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final StudentQueryService service = new StudentQueryService(studentRepository, studentGuardianRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void list_excludesInactiveByDefault() {
        given(studentRepository.findByTenantIdAndActiveTrue(TENANT_ID)).willReturn(List.of(student(10L, true)));

        List<StudentResponse> result = service.list(authUser(), TENANT_ID, false);

        assertThat(result).extracting(StudentResponse::id).containsExactly(10L);
        assertThat(result).allMatch(StudentResponse::active);
        verify(studentRepository, never()).findByTenantId(TENANT_ID);   // 활성 필터를 우회하지 않는다
    }

    @Test
    void list_includeInactive_returnsAll() {
        given(studentRepository.findByTenantId(TENANT_ID))
                .willReturn(List.of(student(10L, true), student(11L, false)));

        List<StudentResponse> result = service.list(authUser(), TENANT_ID, true);

        assertThat(result).extracting(StudentResponse::id).containsExactly(10L, 11L);
        assertThat(result).extracting(StudentResponse::active).containsExactly(true, false);
        verify(studentRepository, never()).findByTenantIdAndActiveTrue(TENANT_ID);
    }

    private Student student(Long id, boolean active) {
        Student student = Student.builder().tenant(tenant()).name("학생" + id).build();
        ReflectionTestUtils.setField(student, "id", id);
        if (!active) {
            student.deactivate();
        }
        return student;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private AuthUser authUser() {
        return new AuthUser(100L, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }
}
