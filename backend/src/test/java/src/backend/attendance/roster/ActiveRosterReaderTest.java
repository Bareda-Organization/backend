package src.backend.attendance.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.entity.AttendanceType;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;

/**
 * "당일 실제 명단" 규칙 단위 테스트 — {@code AttendanceQueryService} 에 있던 로스터 조회를
 * {@link ActiveRosterReader} 로 옮기면서(§7: Command 가 Query 를 호출하지 않는다) 규칙이
 * 그대로임을 고정한다. 기사 앱 명단·노선 계산·시뮬레이션 셋이 이 규칙 하나를 공유하므로,
 * 여기서 판정이 바뀌면 세 화면이 동시에 바뀐다.
 *
 * <p>핵심은 <b>승인된 신고만 제외</b>한다는 것 — 신청(PENDING)·반려(REJECTED)는 여전히 명단에 남는다.
 */
class ActiveRosterReaderTest {

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 10L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final AttendanceExceptionRepository attendanceExceptionRepository =
            mock(AttendanceExceptionRepository.class);

    private final ActiveRosterReader reader =
            new ActiveRosterReader(studentRepository, attendanceExceptionRepository);

    @Test
    void forBus_excludesOnlyApprovedExceptions() {
        Student approved = student(101L);
        Student pending = student(102L);
        Student rejected = student(103L);
        Student clean = student(104L);
        given(studentRepository.findByAssignedBusIdAndActiveTrue(BUS_ID))
                .willReturn(List.of(approved, pending, rejected, clean));
        givenException(101L, exception(101L, true, false));
        givenException(102L, exception(102L, false, false));    // 신청만 하고 아직 미처리
        givenException(103L, exception(103L, false, true));     // 반려됨 = 등하원한다
        givenException(104L);

        List<Student> roster = reader.forBus(BUS_ID, DATE);

        assertThat(roster).extracting(Student::getId).containsExactly(102L, 103L, 104L);
    }

    @Test
    void forBus_differentDateException_doesNotAffectRoster() {
        Student student = student(101L);
        given(studentRepository.findByAssignedBusIdAndActiveTrue(BUS_ID)).willReturn(List.of(student));
        // 저장소 조회 자체가 (studentId, targetDate) 로 걸린다 — 다른 날짜 신고는 애초에 조회되지 않는다
        givenException(101L);

        assertThat(reader.forBus(BUS_ID, DATE)).extracting(Student::getId).containsExactly(101L);
    }

    @Test
    void forTenant_looksAtWholeTenantIncludingUnassignedStudents() {
        Student unassigned = student(201L);   // assignedBus 없음 — F4 자동배차의 대상이다
        Student absent = student(202L);
        given(studentRepository.findByTenantIdAndActiveTrue(TENANT_ID)).willReturn(List.of(unassigned, absent));
        givenException(201L);
        givenException(202L, exception(202L, true, false));

        List<Student> roster = reader.forTenant(TENANT_ID, DATE);

        assertThat(roster).extracting(Student::getId).containsExactly(201L);
    }

    // ── fixtures ──

    private void givenException(Long studentId, AttendanceException... exceptions) {
        given(attendanceExceptionRepository.findByStudentIdAndTargetDate(studentId, DATE))
                .willReturn(List.of(exceptions));
    }

    private AttendanceException exception(Long studentId, boolean approve, boolean reject) {
        AttendanceException exception = AttendanceException.builder()
                .tenantId(TENANT_ID).studentId(studentId).type(AttendanceType.ABSENCE)
                .targetDate(DATE).reason("가족 여행").build();
        if (approve) {
            exception.approve(999L);
        } else if (reject) {
            exception.reject(999L);
        }
        return exception;
    }

    private Student student(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").lat(37.500).lng(127.000).build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        Student student = Student.builder().tenant(tenant).name("학생" + id).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }
}
