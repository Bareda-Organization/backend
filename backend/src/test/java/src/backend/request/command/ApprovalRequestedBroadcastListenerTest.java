package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.request.entity.ChangeRequest;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@code approval_requested} 방송의 채널 audience — §7 채널 표가 학원 관제 채널 전용으로
 * 못박은 이벤트라, 매니저·학생·관리자 채널로는 절대 나가지 않는다는 것이 핵심이다.
 */
class ApprovalRequestedBroadcastListenerTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final ChangeRequestRepository changeRequestRepository = mock(ChangeRequestRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final ApprovalRequestedBroadcastListener listener = new ApprovalRequestedBroadcastListener(
            studentRepository, runRiderRepository, stopRepository, changeRequestRepository, gateway, Runnable::run);

    @Test
    @DisplayName("학원 관제 채널로만 나간다, 다른 3채널은 호출조차 되지 않는다")
    void 학원_채널_전용으로_방송된다() {
        Long changeRequestId = 1L;
        Long academyId = 1L;
        Long runId = 10L;
        Long studentId = 100L;
        OffsetDateTime requestedAt = OffsetDateTime.now();
        ApprovalRequestedEvent event = new ApprovalRequestedEvent(changeRequestId, academyId, runId, studentId,
                requestedAt);

        Student student = mock(Student.class);
        given(student.getName()).willReturn("이서연");
        given(studentRepository.findAllByAcademyIdAndIdIn(academyId, List.of(studentId))).willReturn(List.of(student));

        RunRider rider = RunRider.uponConfirmation(academyId, runId, studentId, 7L);
        given(runRiderRepository.findByRunIdAndStudentId(runId, studentId)).willReturn(Optional.of(rider));

        Stop stop = mock(Stop.class);
        given(stop.getName()).willReturn("정문 앞");
        given(stopRepository.findByIdAndAcademyId(7L, academyId)).willReturn(Optional.of(stop));

        ChangeRequest changeRequest = mock(ChangeRequest.class);
        OffsetDateTime deadline = requestedAt.plusHours(1);
        given(changeRequest.getDeadlineAt()).willReturn(deadline);
        given(changeRequestRepository.findByIdAndAcademyId(changeRequestId, academyId)).willReturn(Optional.of(changeRequest));

        listener.broadcast(event);

        // 다른 3채널로는 나가지 않는다 — 학생 개인 채널·매니저 채널·관리자 채널 전부 미호출.
        verify(gateway, never()).send(eq(WebSocketDestinations.studentRun(studentId)), any(), any(), any(), any());
        verify(gateway, never()).send(eq(WebSocketDestinations.managerRun(runId)), any(), any(), any(), any());
        verify(gateway, never()).send(eq(WebSocketDestinations.ADMIN_LIVE), any(), any(), any(), any());
        verify(gateway, never()).broadcastToRunChannels(anyLong(), anyLong(), any(), any(), any(), any());

        verify(gateway).send(eq(WebSocketDestinations.academyLive(academyId)), eq("approval_requested"), eq(runId),
                eq(requestedAt), argThat(payload -> {
                    String text = String.valueOf(payload);
                    assertThat(text).contains("이서연").contains("정문 앞").contains(deadline.toString());
                    return true;
                }));
    }

    @Test
    @DisplayName("이벤트의 학원 밖 행은 읽지 않는다 — 학생 이름 · 정차지 이름 · 마감이 비어 나간다")
    void 다른_학원의_행은_싣지_않는다() {
        Long academyId = 1L;
        Long runId = 10L;
        Long studentId = 100L;
        OffsetDateTime requestedAt = OffsetDateTime.now();
        ApprovalRequestedEvent event = new ApprovalRequestedEvent(1L, academyId, runId, studentId, requestedAt);

        // 학원 조건 없이 id 로만 읽으면 다른 학원의 행이 잡히도록 깔아 둔다
        Student foreignStudent = mock(Student.class);
        given(foreignStudent.getName()).willReturn("남의 학원 학생");
        given(studentRepository.findById(studentId)).willReturn(Optional.of(foreignStudent));
        given(runRiderRepository.findByRunIdAndStudentId(runId, studentId))
                .willReturn(Optional.of(RunRider.uponConfirmation(academyId, runId, studentId, 7L)));
        Stop foreignStop = mock(Stop.class);
        given(foreignStop.getName()).willReturn("남의 학원 정차지");
        given(stopRepository.findById(7L)).willReturn(Optional.of(foreignStop));
        ChangeRequest foreignRequest = mock(ChangeRequest.class);
        given(foreignRequest.getDeadlineAt()).willReturn(requestedAt.plusHours(1));
        given(changeRequestRepository.findById(1L)).willReturn(Optional.of(foreignRequest));

        listener.broadcast(event);

        verify(gateway).send(eq(WebSocketDestinations.academyLive(academyId)), eq("approval_requested"), eq(runId),
                eq(requestedAt), argThat(payload -> {
                    assertThat(String.valueOf(payload)).doesNotContain("남의 학원");
                    return true;
                }));
    }
}
