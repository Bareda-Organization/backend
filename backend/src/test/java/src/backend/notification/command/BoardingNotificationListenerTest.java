package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.NoShowParentComposer;
import src.backend.notification.domain.impl.NoShowStaffComposer;
import src.backend.notification.domain.impl.RiderStatusChangedComposer;
import src.backend.run.event.StopDepartedEvent;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승하차 알림 적재 — BR-134(승하차지의 학생 수만큼 보호자를 따로 조회하지 않고 한 번에 읽음, 이름
 * 출처는 {@code guardian.name} 그대로).
 */
class BoardingNotificationListenerTest {

    private static final long ACADEMY_ID = 1L;
    private static final long RUN_ID = 10L;
    private static final long STOP_ID = 20L;
    private static final OffsetDateTime DEPARTED_AT = OffsetDateTime.parse("2030-04-01T08:10:00+09:00");

    private final GuardianStudentRepository guardianStudentRepository = mock(GuardianStudentRepository.class);
    private final AcademyStaffRepository academyStaffRepository = mock(AcademyStaffRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    private final BoardingNotificationListener listener = new BoardingNotificationListener(
            guardianStudentRepository, academyStaffRepository, studentRepository, runRiderRepository, outbox,
            new RiderStatusChangedComposer(), new NoShowParentComposer(), new NoShowStaffComposer());

    BoardingNotificationListenerTest() {
        List<RunRider> finalized = List.of(rider(1L, 101L), rider(2L, 102L), rider(3L, 103L));
        List<GuardianAccountRecipient> guardians = List.of(
                guardian(101L, 11L, "영희엄마", "김영희"),
                guardian(101L, 12L, "영희아빠", "김영희"),
                guardian(102L, 13L, "철수엄마", "박철수"),
                guardian(103L, 14L, "민수엄마", "이민수"));
        given(runRiderRepository.findFinalizedByRunIdAndStopId(RUN_ID, STOP_ID)).willReturn(finalized);
        given(guardianStudentRepository.findActiveGuardianAccountsByStudentIds(eq(ACADEMY_ID),
                eq(List.of(101L, 102L, 103L)))).willReturn(guardians);
    }

    @Test
    void 승하차지_학생마다_보호자를_다시_조회하지_않는다() {
        listener.appendStopDeparted(new StopDepartedEvent(RUN_ID, ACADEMY_ID, STOP_ID, DEPARTED_AT));

        verify(guardianStudentRepository, times(1))
                .findActiveGuardianAccountsByStudentIds(anyLong(), anyList());
    }

    @Test
    void 수신자_이름은_guardian_name_그대로다() {
        listener.appendStopDeparted(new StopDepartedEvent(RUN_ID, ACADEMY_ID, STOP_ID, DEPARTED_AT));

        List<NotificationDraft> drafts = drafts();
        assertThat(drafts).hasSize(4);
        assertThat(nameOf(drafts, 11L)).isEqualTo("영희엄마");
        assertThat(nameOf(drafts, 12L)).isEqualTo("영희아빠");
        assertThat(nameOf(drafts, 13L)).isEqualTo("철수엄마");
        assertThat(nameOf(drafts, 14L)).isEqualTo("민수엄마");
    }

    private static List<Long> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    /** 정차지 출발의 알림 초안은 한 번의 묶음 적재({@code appendAll})로 나간다(BR-374) — 건별 {@code append} 가 아니다. */
    @SuppressWarnings("unchecked")
    private List<NotificationDraft> drafts() {
        ArgumentCaptor<List<NotificationDraft>> drafts = ArgumentCaptor
                .forClass((Class<List<NotificationDraft>>) (Class<?>) List.class);
        verify(outbox, times(1)).appendAll(drafts.capture());
        org.assertj.core.api.Assertions.assertThat(drafts.getValue()).hasSize(4);
        return drafts.getValue();
    }

    private static String nameOf(List<NotificationDraft> drafts, long accountId) {
        return drafts.stream()
                .filter(draft -> draft.recipientRole() == Role.PARENT && draft.recipientAccountId() == accountId)
                .map(NotificationDraft::recipientName)
                .findFirst().orElseThrow();
    }

    private static RunRider rider(long id, long studentId) {
        RunRider rider = RunRider.uponConfirmation(RUN_ID, studentId, STOP_ID);
        rider.board(DEPARTED_AT);
        ReflectionTestUtils.setField(rider, "id", id);
        return rider;
    }

    private static GuardianAccountRecipient guardian(long studentId, long accountId, String name,
            String studentName) {
        GuardianAccountRecipient guardian = mock(GuardianAccountRecipient.class);
        given(guardian.getStudentId()).willReturn(studentId);
        given(guardian.getAccountId()).willReturn(accountId);
        given(guardian.getName()).willReturn(name);
        given(guardian.getStudentName()).willReturn(studentName);
        return guardian;
    }
}
