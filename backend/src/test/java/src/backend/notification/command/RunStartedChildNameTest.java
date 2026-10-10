package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.RunStartedChildComposer;
import src.backend.notification.domain.impl.RunStartedComposer;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * Ruling 868 — 학부모·학생이 받는 {@code run_started} 문구에는 자녀 이름이 들어가고(형제가 같은 회차를 타면 문구로
 * 누구 버스인지 가를 수 있다), 관계자 문구는 그대로다.
 */
class RunStartedChildNameTest {

    private static final long RUN_ID = 100L;

    private static final long ACADEMY_ID = 1L;

    private static final long GUARDIAN_ACCOUNT = 11L;

    private static final long STUDENT_ACCOUNT = 21L;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    @Test
    void 학부모와_학생_문구에는_자녀_이름이_들어가고_형제는_문구가_갈린다() {
        List<NotificationDraft> drafts = appendRunStarted();

        List<NotificationDraft> parents = drafts.stream().filter(d -> d.recipientRole() == Role.PARENT).toList();
        assertThat(parents).hasSize(2);
        assertThat(parents).filteredOn(d -> d.studentId() == 50L).singleElement()
                .satisfies(d -> assertThat(d.body()).contains("첫째"));
        assertThat(parents).filteredOn(d -> d.studentId() == 51L).singleElement()
                .satisfies(d -> assertThat(d.body()).contains("둘째").doesNotContain("첫째"));
        assertThat(drafts).filteredOn(d -> d.recipientRole() == Role.STUDENT).singleElement()
                .satisfies(d -> assertThat(d.body()).contains("첫째"));
    }

    @Test
    void 관계자_문구는_자녀_이름_없이_그대로다() {
        List<NotificationDraft> drafts = appendRunStarted();

        assertThat(drafts).filteredOn(d -> d.recipientRole() == Role.STAFF).singleElement()
                .satisfies(d -> assertThat(d.body()).isEqualTo("배정된 회차의 운행이 시작되었습니다."));
    }

    private List<NotificationDraft> appendRunStarted() {
        AcademyStaffRepository staffRepository = mock(AcademyStaffRepository.class);
        RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
        GuardianStudentRepository guardianStudentRepository = mock(GuardianStudentRepository.class);
        StudentRepository studentRepository = mock(StudentRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        given(staffRepository.findActiveAccountsByAcademyId(ACADEMY_ID))
                .willReturn(List.of(new AcademyStaffAccountView(31L, "관계자")));
        given(runRiderRepository.findAllByRunIdAndAcademyId(RUN_ID, ACADEMY_ID)).willReturn(
                List.of(RunRider.uponConfirmation(RUN_ID, 50L, 1L), RunRider.uponConfirmation(RUN_ID, 51L, 1L)));
        given(guardianStudentRepository.findGuardianAccountsByAcademyId(ACADEMY_ID, List.of(50L, 51L)))
                .willReturn(List.of(guardian(50L, "첫째"), guardian(51L, "둘째")));
        Student 첫째 = Student.register(ACADEMY_ID, new StudentProfile("첫째", null, null, null, null, null, null, null, null));
        org.springframework.test.util.ReflectionTestUtils.setField(첫째, "id", 50L);
        org.springframework.test.util.ReflectionTestUtils.setField(첫째, "accountId", STUDENT_ACCOUNT);
        given(studentRepository.findAllByIdInAndAcademyIdAndAccountIdIsNotNull(List.of(50L, 51L), ACADEMY_ID))
                .willReturn(List.of(첫째));
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());

        new RunStartedNotificationListener(staffRepository, runRiderRepository, guardianStudentRepository,
                studentRepository, runRepository, mock(BusRepository.class), outbox, new RunStartedComposer(), new RunStartedChildComposer())
                .appendRunStarted(new RunStartedEvent(RUN_ID, ACADEMY_ID, AT, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationDraft>> batch = ArgumentCaptor.forClass(List.class);
        verify(outbox).appendAll(batch.capture());
        return batch.getValue();
    }

    private static GuardianAccountRecipient guardian(long studentId, String studentName) {
        return new GuardianAccountRecipient() {
            @Override
            public Long getStudentId() {
                return studentId;
            }

            @Override
            public Long getAccountId() {
                return GUARDIAN_ACCOUNT;
            }

            @Override
            public String getName() {
                return "보호자";
            }

            @Override
            public String getStudentName() {
                return studentName;
            }
        };
    }
}
