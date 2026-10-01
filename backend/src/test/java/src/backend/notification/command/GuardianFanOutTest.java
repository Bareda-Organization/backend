package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Role;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * BR-073 — 학생 1명에 보호자가 여럿이면(Ruling 326 — 정상 상태) 운행 시작·곧 도착·등원 종료 자동 하차
 * 알림도 승하차 알림({@code BoardingNotificationListener})처럼 연결된 보호자 전원이 받는다(§9.7 수신자
 * "학부모", 수 제한 부재).
 */
class GuardianFanOutTest {

    private static final long RUN_ID = 100L;

    private static final long ACADEMY_ID = 1L;

    private static final long STUDENT_ID = 50L;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    private final GuardianStudentRepository guardianStudentRepository = mock(GuardianStudentRepository.class);

    private final StudentRepository studentRepository = mock(StudentRepository.class);

    GuardianFanOutTest() {
        given(guardianStudentRepository.findGuardianAccountsByAcademyId(ACADEMY_ID, List.of(STUDENT_ID)))
                .willReturn(List.of(guardian(11L, "엄마"), guardian(12L, "아빠")));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private <T> NotificationComposer<T> composer() {
        NotificationComposer<T> composer = mock(NotificationComposer.class);
        given(composer.compose(any())).willReturn(new NotificationMessage("제목", "본문"));
        return composer;
    }

    @Test
    void 운행시작은_보호자_전원에게_적재된다() {
        RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        given(runRiderRepository.findAllByRunIdAndAcademyId(RUN_ID, ACADEMY_ID))
                .willReturn(List.of(RunRider.uponConfirmation(RUN_ID, STUDENT_ID, 1L)));
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());

        new RunStartedNotificationListener(mock(AcademyStaffRepository.class), runRiderRepository,
                guardianStudentRepository, studentRepository, runRepository, mock(BusRepository.class), outbox,
                composer()).appendRunStarted(new RunStartedEvent(RUN_ID, ACADEMY_ID, AT, 0));

        assertThat(parentRecipients()).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    void 곧_도착은_보호자_전원에게_적재된다() {
        new RunApproachingStopNotificationListener(guardianStudentRepository, studentRepository, outbox, composer())
                .appendRunApproachingStop(new RunApproachingStopEvent(RUN_ID, ACADEMY_ID, STUDENT_ID, 7L, AT));

        assertThat(parentRecipients()).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    void 등원_종료_자동하차는_보호자_전원에게_적재된다() {
        new RunAutoAlightedNotificationListener(guardianStudentRepository, outbox, composer())
                .appendRunAutoAlighted(new RunAutoAlightedEvent(RUN_ID, ACADEMY_ID, STUDENT_ID, AT));

        assertThat(parentRecipients()).containsExactlyInAnyOrder(11L, 12L);
    }

    private List<Long> parentRecipients() {
        List<NotificationDraft> parents = appendedDrafts().stream()
                .filter(draft -> draft.recipientRole() == Role.PARENT).toList();
        assertThat(parents.stream().map(NotificationDraft::dedupKey)).doesNotHaveDuplicates();
        return parents.stream().map(NotificationDraft::recipientAccountId).toList();
    }

    private static GuardianAccountRecipient guardian(long accountId, String name) {
        return new GuardianAccountRecipient() {
            @Override
            public Long getStudentId() {
                return STUDENT_ID;
            }

            @Override
            public Long getAccountId() {
                return accountId;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getStudentName() {
                return "학생";
            }
        };
    }

    /** 적재 호출 전부 — 건별 {@code append} 와 묶음 {@code appendAll}(R46 T-6)을 한 목록으로 모은다. */
    private List<NotificationDraft> appendedDrafts() {
        ArgumentCaptor<NotificationDraft> single = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(outbox, atLeast(0)).append(single.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationDraft>> batches = ArgumentCaptor.forClass(List.class);
        verify(outbox, atLeast(0)).appendAll(batches.capture());
        List<NotificationDraft> all = new ArrayList<>(single.getAllValues());
        batches.getAllValues().forEach(all::addAll);
        return all;
    }
}
