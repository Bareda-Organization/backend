package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.notification.domain.impl.DelayComposer;
import src.backend.notification.domain.impl.NoShowParentComposer;
import src.backend.notification.domain.impl.NotificationTexts;
import src.backend.run.dto.DelayNoticeRecipient;
import src.backend.run.entity.DelayReason;
import src.backend.run.entity.Run;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.event.StopDepartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 알림 리스너는 한 수신자의 재료가 잘못돼도 그 초안만 건너뛰고 WARN 을 남긴다 — 리스너는 업무 트랜잭션 안에서 도는 동기 리스너라 예외를
 * 던지면 출발 처리·지연 신고 전체가 롤백된다. 학생 행을 못 찾은 학부모·학생 수신자에게 관계자용 문구(자녀 이름 없음)를 보내지도 않는다.
 */
class ListenerSkipOnBadInputTest {

    private static final long ACADEMY_ID = 1L;

    private static final long RUN_ID = 10L;

    private static final long STOP_ID = 20L;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");

    private final NotificationOutbox outbox = mock(NotificationOutbox.class);

    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final GuardianStudentRepository guardianStudentRepository = mock(GuardianStudentRepository.class);

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);

    private final RunRepository runRepository = mock(RunRepository.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger delayLogger = (Logger) LoggerFactory.getLogger(DelayNotificationListener.class);

    private final Logger boardingLogger = (Logger) LoggerFactory.getLogger(BoardingNotificationListener.class);

    @BeforeEach
    void 로그를_모은다() {
        appender.start();
        delayLogger.addAppender(appender);
        boardingLogger.addAppender(appender);
    }

    @AfterEach
    void 로그_수집을_끝낸다() {
        delayLogger.detachAppender(appender);
        boardingLogger.detachAppender(appender);
    }

    @Test
    void 학생_행을_못_찾은_학부모와_학생_수신자는_건너뛰고_WARN_을_남긴다() {
        given(runRepository.findByIdAndAcademyId(anyLong(), anyLong())).willReturn(Optional.empty());
        Student found = student(22L, "박철수", 900L);
        given(studentRepository.findAllByAcademyIdAndIdIn(any(), any())).willReturn(List.of(found));
        given(studentRepository.findAllByAcademyIdAndAccountIdIn(any(), any())).willReturn(List.of());
        DelayNotificationListener listener = new DelayNotificationListener(outbox, new DelayComposer(),
                studentRepository, runRepository, mock(BusRepository.class));
        DelayRequestedEvent event = new DelayRequestedEvent(100L, ACADEMY_ID, 10, DelayReason.TRAFFIC, null, AT,
                List.of(new DelayNoticeRecipient(5L, "관계자", 5L)),
                List.of(new DelayNoticeRecipient(11L, "영희엄마", 21L), new DelayNoticeRecipient(12L, "철수아빠", 22L)),
                List.of(new DelayNoticeRecipient(901L, "없는학생", 901L)));

        assertThatCode(() -> listener.appendDelayNotice(event)).doesNotThrowAnyException();

        List<NotificationDraft> drafts = delayDrafts();
        assertThat(drafts).extracting(NotificationDraft::recipientAccountId).containsExactlyInAnyOrder(5L, 12L);
        assertThat(warns()).hasSize(2);
    }

    @Test
    void 예상하지_못한_탑승_상태는_그_학생의_초안만_건너뛰고_출발_처리는_계속된다() {
        RunRider waiting = RunRider.uponConfirmation(RUN_ID, 101L, STOP_ID);
        ReflectionTestUtils.setField(waiting, "id", 1L);
        RunRider boarded = RunRider.uponConfirmation(RUN_ID, 102L, STOP_ID);
        boarded.board(AT);
        ReflectionTestUtils.setField(boarded, "id", 2L);
        given(runRiderRepository.findFinalizedByRunIdAndStopId(RUN_ID, STOP_ID)).willReturn(List.of(waiting, boarded));
        Run run = mock(Run.class);
        given(run.getDirection()).willReturn(Direction.TO_ACADEMY);
        given(runRepository.findByIdAndAcademyId(RUN_ID, ACADEMY_ID)).willReturn(Optional.of(run));
        GuardianAccountRecipient mother = guardian(101L, 11L, "영희엄마", "김영희");
        GuardianAccountRecipient father = guardian(102L, 12L, "철수엄마", "박철수");
        given(guardianStudentRepository.findGuardianAccountsByAcademyId(eq(ACADEMY_ID), any()))
                .willReturn(List.of(mother, father));
        NotificationTexts texts = new NotificationTexts();
        BoardingNotificationListener listener = new BoardingNotificationListener(guardianStudentRepository,
                mock(AcademyStaffRepository.class), studentRepository, runRiderRepository, runRepository, outbox,
                texts.riderStatusChangedComposer(), new NoShowParentComposer(), texts.noShowStaffComposer());

        assertThatCode(() -> listener.appendStopDeparted(new StopDepartedEvent(RUN_ID, ACADEMY_ID, STOP_ID, AT)))
                .doesNotThrowAnyException();

        assertThat(delayDrafts()).extracting(NotificationDraft::recipientAccountId).containsExactly(12L);
        assertThat(warns()).hasSize(1);
    }

    private List<ILoggingEvent> warns() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @SuppressWarnings("unchecked")
    private List<NotificationDraft> delayDrafts() {
        ArgumentCaptor<List<NotificationDraft>> drafts = ArgumentCaptor
                .forClass((Class<List<NotificationDraft>>) (Class<?>) List.class);
        verify(outbox, atLeastOnce()).appendAll(drafts.capture());
        return drafts.getValue();
    }

    private static Student student(long id, String name, Long accountId) {
        Student student = Student.register(ACADEMY_ID,
                new StudentProfile(name, null, null, null, null, null, null, null, null));
        ReflectionTestUtils.setField(student, "id", id);
        ReflectionTestUtils.setField(student, "accountId", accountId);
        return student;
    }

    private static GuardianAccountRecipient guardian(long studentId, long accountId, String name, String studentName) {
        GuardianAccountRecipient guardian = mock(GuardianAccountRecipient.class);
        given(guardian.getStudentId()).willReturn(studentId);
        given(guardian.getAccountId()).willReturn(accountId);
        given(guardian.getName()).willReturn(name);
        given(guardian.getStudentName()).willReturn(studentName);
        return guardian;
    }
}
