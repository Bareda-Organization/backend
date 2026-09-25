package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.DelayComposer;
import src.backend.run.entity.DelayReason;
import src.backend.run.event.DelayNoticeRecipient;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.StudentRepository;

/**
 * 지연 알림 적재 — BR-077(학부모·학생 문구에 자녀 이름, ATT-03 · Ruling 225) · BR-143(수신자마다 학생을
 * 다시 조회하지 않음).
 */
class DelayNotificationListenerTest {

    private static final long ACADEMY_ID = 1L;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final RunRepository runRepository = mock(RunRepository.class);

    private final DelayNotificationListener listener = new DelayNotificationListener(outbox, new DelayComposer(),
            studentRepository, runRepository, mock(BusRepository.class));

    DelayNotificationListenerTest() {
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());
        Student younghee = student(21L, "김영희", null);
        Student cheolsu = student(22L, "박철수", 900L);
        given(studentRepository.findAllByAcademyIdAndIdIn(any(), any())).willReturn(List.of(younghee, cheolsu));
        given(studentRepository.findAllByAcademyIdAndAccountIdIn(any(), any())).willReturn(List.of(cheolsu));
        given(studentRepository.findById(21L)).willReturn(Optional.of(younghee));
        given(studentRepository.findById(22L)).willReturn(Optional.of(cheolsu));
        given(studentRepository.findByAccountId(900L)).willReturn(Optional.of(cheolsu));
    }

    @Test
    void 학부모와_학생_문구에는_자녀_이름이_실린다() {
        listener.appendDelayNotice(event());

        List<NotificationDraft> drafts = drafts();
        assertThat(bodyOf(drafts, Role.PARENT, 11L)).contains("김영희");
        assertThat(bodyOf(drafts, Role.PARENT, 12L)).contains("박철수");
        assertThat(bodyOf(drafts, Role.STUDENT, 900L)).contains("박철수");
        assertThat(bodyOf(drafts, Role.STAFF, 5L)).doesNotContain("김영희").doesNotContain("박철수");
    }

    @Test
    void 수신자마다_학생을_다시_조회하지_않는다() {
        listener.appendDelayNotice(event());

        verify(studentRepository, never()).findById(anyLong());
        verify(studentRepository, never()).findByAccountId(anyLong());
    }

    private DelayRequestedEvent event() {
        return new DelayRequestedEvent(100L, ACADEMY_ID, 10, DelayReason.TRAFFIC, null, AT,
                List.of(new DelayNoticeRecipient(5L, "관계자", 5L)),
                List.of(new DelayNoticeRecipient(11L, "영희엄마", 21L), new DelayNoticeRecipient(12L, "철수아빠", 22L)),
                List.of(new DelayNoticeRecipient(900L, "박철수", 900L)));
    }

    private List<NotificationDraft> drafts() {
        ArgumentCaptor<NotificationDraft> drafts = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(outbox, atLeastOnce()).append(drafts.capture());
        return drafts.getAllValues();
    }

    private static String bodyOf(List<NotificationDraft> drafts, Role role, long accountId) {
        return drafts.stream()
                .filter(draft -> draft.recipientRole() == role && draft.recipientAccountId() == accountId)
                .map(NotificationDraft::body)
                .findFirst().orElseThrow();
    }

    private static Student student(long id, String name, Long accountId) {
        Student student = Student.register(ACADEMY_ID,
                new StudentProfile(name, null, null, null, null, null, null, null, null));
        ReflectionTestUtils.setField(student, "id", id);
        ReflectionTestUtils.setField(student, "accountId", accountId);
        return student;
    }
}
