package src.backend.request.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.boarding.entity.RunRider;
import src.backend.request.dto.AffectedStudentResponse;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/** BR-134 — 영향 학생 이름을 학생마다 따로 조회하지 않고 한 번에 읽는다. */
class RoutePreviewAssemblerTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final RoutePreviewAssembler assembler = new RoutePreviewAssembler(mock(StopRepository.class),
            studentRepository);

    @Test
    void 영향_학생_이름은_한_번에_읽는다() {
        Student a = student(2L, "학생A");
        Student b = student(3L, "학생B");
        given(studentRepository.findAllById(any())).willReturn(List.of(a, b));
        given(studentRepository.findById(2L)).willReturn(Optional.of(a));
        given(studentRepository.findById(3L)).willReturn(Optional.of(b));
        List<RunRider> riders = List.of(RunRider.uponConfirmation(10L, 2L, 100L), RunRider.uponConfirmation(10L, 3L,
                101L));

        List<AffectedStudentResponse> affected = assembler.affectedStudentsOf(1L, "대상", riders,
                Map.of(100L, 1, 101L, 2), Map.of(100L, 2, 101L, 1));

        assertThat(affected).extracting(AffectedStudentResponse::name).containsExactly("대상", "학생A", "학생B");
        verify(studentRepository, never()).findById(anyLong());
    }

    private static Student student(long id, String name) {
        Student student = Student.register(1L, new StudentProfile(name, null, null, null, null, null, null, null,
                null));
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }
}
