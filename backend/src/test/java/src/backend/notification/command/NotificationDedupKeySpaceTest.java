package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
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
import src.backend.notification.domain.impl.DelaySubject;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.run.event.DelayNoticeRecipient;
import src.backend.run.entity.DelayReason;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * BR-007 — {@code run_started}·{@code delay} 멱등키의 대상 자리에 계정 ID 와 학생 ID 가 섞이면, 두
 * 시퀀스가 모두 1 부터라 관계자 계정 ID 와 같은 학원 학생 ID 가 같을 때 두 행이 같은 키가 되어 운행
 * 시작·지연 신고 트랜잭션 전체가 {@code DUPLICATE_NOTIFICATION} 으로 롤백된다.
 */
class NotificationDedupKeySpaceTest {

    private static final long RUN_ID = 100L;

    private static final long ACADEMY_ID = 1L;

    /** 관계자 계정 ID 와 학생 ID 가 같은 값 — 두 시퀀스가 독립이라 실제로 겹친다(시드 V2 staffA=2 · 김영희=2). */
    private static final long COLLIDING_ID = 7L;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    @SuppressWarnings("unchecked")
    private <T> NotificationComposer<T> composer() {
        NotificationComposer<T> composer = mock(NotificationComposer.class);
        given(composer.compose(any())).willReturn(new NotificationMessage("제목", "본문"));
        return composer;
    }

    @Test
    void 운행시작_관계자_계정ID와_학생ID가_같아도_멱등키가_겹치지_않는다() {
        AcademyStaffRepository staffRepository = mock(AcademyStaffRepository.class);
        RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
        GuardianStudentRepository guardianStudentRepository = mock(GuardianStudentRepository.class);
        StudentRepository studentRepository = mock(StudentRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        given(staffRepository.findActiveAccountsByAcademyId(ACADEMY_ID))
                .willReturn(List.of(new AcademyStaffAccountView(COLLIDING_ID, "관계자")));
        given(runRiderRepository.findAllByRunIdAndAcademyId(RUN_ID, ACADEMY_ID))
                .willReturn(List.of(RunRider.uponConfirmation(RUN_ID, COLLIDING_ID, 1L)));
        given(guardianStudentRepository.findGuardianAccountsByAcademyId(ACADEMY_ID, List.of(COLLIDING_ID)))
                .willReturn(List.of(guardian(COLLIDING_ID, 9L)));
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());

        new RunStartedNotificationListener(staffRepository, runRiderRepository, guardianStudentRepository,
                studentRepository, runRepository, mock(BusRepository.class), outbox, composer())
                .appendRunStarted(new RunStartedEvent(RUN_ID, ACADEMY_ID, AT, 0));

        assertThat(appendedKeys()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void 지연_관계자_계정ID와_학부모_대상_학생ID가_같아도_멱등키가_겹치지_않는다() {
        StudentRepository studentRepository = mock(StudentRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());
        DelayRequestedEvent event = new DelayRequestedEvent(RUN_ID, ACADEMY_ID, 10, DelayReason.TRAFFIC, null, AT,
                List.of(new DelayNoticeRecipient(COLLIDING_ID, "관계자", COLLIDING_ID)),
                List.of(new DelayNoticeRecipient(9L, "보호자", COLLIDING_ID)),
                List.of());

        new DelayNotificationListener(outbox, this.<DelaySubject>composer(), studentRepository, runRepository,
                mock(BusRepository.class)).appendDelayNotice(event);

        assertThat(appendedKeys()).hasSize(2).doesNotHaveDuplicates();
    }

    private List<String> appendedKeys() {
        ArgumentCaptor<NotificationDraft> drafts = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(outbox, atLeastOnce()).append(drafts.capture());
        return drafts.getAllValues().stream().map(NotificationDraft::dedupKey).toList();
    }

    private static GuardianAccountRecipient guardian(long studentId, long accountId) {
        return new GuardianAccountRecipient() {
            @Override
            public Long getStudentId() {
                return studentId;
            }

            @Override
            public Long getAccountId() {
                return accountId;
            }

            @Override
            public String getName() {
                return "보호자";
            }

            @Override
            public String getStudentName() {
                return "학생";
            }
        };
    }
}
