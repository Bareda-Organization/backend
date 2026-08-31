package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@code approval_requested} 방송(목표 4)의 채널 audience — §7 채널 표가 학원 관제 채널 전용으로
 * 못박은 이벤트라, 매니저·학생·관리자 채널로는 절대 나가지 않는다는 것이 핵심이다.
 */
class ApprovalRequestedBroadcastListenerTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final ChangeRequestRepository changeRequestRepository = mock(ChangeRequestRepository.class);
    private final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);

    private final ApprovalRequestedBroadcastListener listener = new ApprovalRequestedBroadcastListener(
            studentRepository, runRiderRepository, stopRepository, changeRequestRepository, gateway);

    @Test
    @DisplayName("목표 4 — 학원 관제 채널로만 나간다, 다른 3채널은 호출조차 되지 않는다")
    void 학원_채널_전용으로_방송된다() {
        Long changeRequestId = 1L;
        Long academyId = 1L;
        Long runId = 10L;
        Long studentId = 100L;
        OffsetDateTime requestedAt = OffsetDateTime.now();
        ApprovalRequestedEvent event = new ApprovalRequestedEvent(changeRequestId, academyId, runId, studentId,
                requestedAt);

        Student student = mock(Student.class);
        when(student.getName()).thenReturn("이서연");
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        RunRider rider = RunRider.uponConfirmation(runId, studentId, 7L);
        when(runRiderRepository.findByRunIdAndStudentId(runId, studentId)).thenReturn(Optional.of(rider));

        Stop stop = mock(Stop.class);
        when(stop.getName()).thenReturn("정문 앞");
        when(stopRepository.findById(7L)).thenReturn(Optional.of(stop));

        ChangeRequest changeRequest = mock(ChangeRequest.class);
        OffsetDateTime deadline = requestedAt.plusHours(1);
        when(changeRequest.getDeadlineAt()).thenReturn(deadline);
        when(changeRequestRepository.findById(changeRequestId)).thenReturn(Optional.of(changeRequest));

        listener.broadcast(event);

        // 다른 3채널로는 나가지 않는다(목표 4 본체) — 학생 개인 채널·매니저 채널·관리자 채널 전부 미호출.
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
}
