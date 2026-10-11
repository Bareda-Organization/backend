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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderRecordedAfterDepartureEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.impl.NoShowParentComposer;
import src.backend.notification.domain.impl.NoShowStaffComposer;
import src.backend.notification.domain.impl.RiderStatusChangedComposer;
import src.backend.notification.entity.NotificationType;
import src.backend.run.entity.Run;
import src.backend.run.event.StopDepartedEvent;
import src.backend.run.repository.RunRepository;
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
    private final RunRepository runRepository = mock(RunRepository.class);
    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    private final BoardingNotificationListener listener = new BoardingNotificationListener(
            guardianStudentRepository, academyStaffRepository, studentRepository, runRiderRepository, runRepository, outbox,
            new RiderStatusChangedComposer(), new NoShowParentComposer(), new NoShowStaffComposer());

    BoardingNotificationListenerTest() {
        List<RunRider> finalized = List.of(rider(1L, 101L), rider(2L, 102L), rider(3L, 103L));
        List<GuardianAccountRecipient> guardians = List.of(
                guardian(101L, 11L, "영희엄마", "김영희"),
                guardian(101L, 12L, "영희아빠", "김영희"),
                guardian(102L, 13L, "철수엄마", "박철수"),
                guardian(103L, 14L, "민수엄마", "이민수"));
        given(runRiderRepository.findFinalizedByRunIdAndStopId(RUN_ID, STOP_ID)).willReturn(finalized);
        givenRunDirection(Direction.TO_ACADEMY);
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

    private void givenRunDirection(Direction direction) {
        Run run = mock(Run.class);
        given(run.getDirection()).willReturn(direction);
        given(runRepository.findByIdAndAcademyId(RUN_ID, ACADEMY_ID)).willReturn(Optional.of(run));
    }

    /** Ruling 878 — 출발 뒤 기록은 기록 즉시 그 학생의 보호자에게만, 출발 때와 같은 종류·문구로 한 번에 적재한다. */
    @Test
    void 출발_뒤_기록은_그_학생의_보호자에게_종류별_문구로_즉시_적재한다() {
        List<GuardianAccountRecipient> guardians = List.of(guardian(101L, 11L, "영희엄마", "김영희"),
                guardian(101L, 12L, "영희아빠", "김영희"));
        given(guardianStudentRepository.findActiveGuardianAccountsByStudentIds(eq(ACADEMY_ID), eq(List.of(101L))))
                .willReturn(guardians);

        listener.appendRecordedAfterDeparture(new RiderRecordedAfterDepartureEvent(RUN_ID, ACADEMY_ID,
                Direction.TO_ACADEMY, 101L, 1L, RiderStatus.BOARDED, DEPARTED_AT));

        List<NotificationDraft> drafts = capturedDrafts();
        assertThat(drafts).hasSize(2);
        assertThat(drafts).extracting(NotificationDraft::type).containsOnly(NotificationType.BOARDING);
        assertThat(drafts).extracting(NotificationDraft::recipientAccountId).containsExactlyInAnyOrder(11L, 12L);
        assertThat(drafts.get(0).body()).isEqualTo("김영희 학생이 버스에 탑승했습니다.");
    }

    @Test
    void 출발_뒤_하원_미승차는_하원_문구와_미승차_종류로_적재한다() {
        List<GuardianAccountRecipient> guardians = List.of(guardian(101L, 11L, "영희엄마", "김영희"));
        given(guardianStudentRepository.findActiveGuardianAccountsByStudentIds(eq(ACADEMY_ID), eq(List.of(101L))))
                .willReturn(guardians);

        listener.appendRecordedAfterDeparture(new RiderRecordedAfterDepartureEvent(RUN_ID, ACADEMY_ID,
                Direction.FROM_ACADEMY, 101L, 1L, RiderStatus.NO_SHOW, DEPARTED_AT));

        NotificationDraft draft = capturedDrafts().get(0);
        assertThat(draft.type()).isEqualTo(NotificationType.NO_SHOW);
        assertThat(draft.body()).isEqualTo("김영희 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.");
    }

    /** Ruling 881 — 출발 때 묶음 발송 경로의 하원 미승차도 하원 문구다(출발 뒤 기록 경로만 검사하면 이 경로의 방향 읽기가 빠져도 통과한다). */
    @Test
    void 출발_묶음_경로의_하원_미승차는_하차_미확인_문구다() {
        long otherStopId = 21L;
        RunRider noShow = RunRider.uponConfirmation(RUN_ID, 104L, otherStopId);
        noShow.markNoShow(DEPARTED_AT);
        ReflectionTestUtils.setField(noShow, "id", 4L);
        given(runRiderRepository.findFinalizedByRunIdAndStopId(RUN_ID, otherStopId)).willReturn(List.of(noShow));
        givenRunDirection(Direction.FROM_ACADEMY);
        List<GuardianAccountRecipient> guardians = List.of(guardian(104L, 15L, "하원엄마", "최하원"));
        given(guardianStudentRepository.findActiveGuardianAccountsByStudentIds(eq(ACADEMY_ID), eq(List.of(104L))))
                .willReturn(guardians);

        listener.appendStopDeparted(new StopDepartedEvent(RUN_ID, ACADEMY_ID, otherStopId, DEPARTED_AT));

        NotificationDraft draft = capturedDrafts().get(0);
        assertThat(draft.type()).isEqualTo(NotificationType.NO_SHOW);
        assertThat(draft.body()).isEqualTo("최하원 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.");
    }

    /** 중복 방지 키는 보호자마다 다르다 — 같은 학생의 보호자 2명이 같은 키를 쓰면 둘째 보호자의 알림이 중복으로 버려진다. */
    @Test
    void 출발_뒤_기록의_중복_방지_키는_보호자마다_다르다() {
        List<GuardianAccountRecipient> guardians = List.of(guardian(101L, 11L, "영희엄마", "김영희"),
                guardian(101L, 12L, "영희아빠", "김영희"));
        given(guardianStudentRepository.findActiveGuardianAccountsByStudentIds(eq(ACADEMY_ID), eq(List.of(101L))))
                .willReturn(guardians);

        listener.appendRecordedAfterDeparture(new RiderRecordedAfterDepartureEvent(RUN_ID, ACADEMY_ID,
                Direction.TO_ACADEMY, 101L, 1L, RiderStatus.BOARDED, DEPARTED_AT));

        List<NotificationDraft> drafts = capturedDrafts();
        assertThat(drafts).extracting(NotificationDraft::dedupKey).hasSize(2).doesNotHaveDuplicates();
        assertThat(drafts).extracting(NotificationDraft::dedupKey)
                .containsExactlyInAnyOrder("recorded_after_departure:1:11:" + DEPARTED_AT,
                        "recorded_after_departure:1:12:" + DEPARTED_AT);
    }

    @SuppressWarnings("unchecked")
    private List<NotificationDraft> capturedDrafts() {
        ArgumentCaptor<List<NotificationDraft>> drafts = ArgumentCaptor
                .forClass((Class<List<NotificationDraft>>) (Class<?>) List.class);
        verify(outbox, times(1)).appendAll(drafts.capture());
        return drafts.getValue();
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
